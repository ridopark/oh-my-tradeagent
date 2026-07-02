package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.WatchlistMirrorPayload;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.Leg;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.ParseResult;
import com.ohmytradeagent.orchestrator.activities.WatchlistParser.TickerWatch;
import com.ohmytradeagent.orchestrator.alert.TenantWebhookResolver;
import com.ohmytradeagent.orchestrator.alert.WebhookClient;
import com.ohmytradeagent.orchestrator.alert.WebhookEmbed;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.workflows.WatchlistDigestMarkerWorkflow;
import com.ohmytradeagent.orchestrator.workflows.WatchlistTriggerSessionWorkflow;
import com.ohmytradeagent.orchestrator.workflows.WatchlistTriggerSessionWorkflowInput;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Formats the verbatim daily watchlist and posts it to the SAME trade-alert Discord webhook ({@code
 * ALERT_DISCORD_WEBHOOK_URL}) via the shared {@link WebhookClient}.
 *
 * <p>Best-effort by construction: {@link WebhookClient} is a no-op on a blank URL and never throws
 * on transport failure, so this activity never disrupts anything. The signal-source-discord sidecar
 * already dedupes by {@code source_message_id} (REJECT_DUPLICATE), so duplicate posts are not
 * expected; an activity retry (max 3) would re-post, but that is cosmetically harmless — there is
 * no order side-effect here.
 *
 * <p><b>Per-{@code (tenant, etDate)} digest dedup:</b> the sidecar fans out one mirror per {@code
 * (tenant, strategy)}, so this activity can run several times for one tenant on one day. To post
 * the tenant's digest exactly ONCE per day, the post is gated on {@link #startDigestMarker} — a
 * {@code REJECT_DUPLICATE} {@link WatchlistDigestMarkerWorkflow} start keyed {@code
 * t-{tenant}/wl/{etDate}/digest}. The entry whose start succeeds posts; the rest see {@link
 * WorkflowExecutionAlreadyStarted} and skip the post. Marker-start happens BEFORE the post so the
 * failure mode is a rare lost (cosmetic) digest on a crash, never a double-post to a real-money
 * channel. The gate covers ONLY the post — {@link #maybeStartTriggerSession} still runs on every
 * invocation regardless.
 */
@Component
public class WatchlistMirrorActivitiesImpl implements WatchlistMirrorActivities {

  /** Discord hard limit for a single message body. */
  private static final int DISCORD_MAX = 2000;

  /** Discord hard limit for a single embed description. */
  private static final int EMBED_DESC_MAX = 4096;

  /** Discord green (0x57F287) as the decimal RGB integer the embed API expects. */
  private static final int DISCORD_GREEN = 5763719;

  private static final String FENCE = "```";
  private static final String TRUNCATION_MARKER = "\n… (truncated)";

  private static final DateTimeFormatter EMBED_DATE =
      DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

  private static final Logger log = LoggerFactory.getLogger(WatchlistMirrorActivitiesImpl.class);

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  // Ingest fan-out collaborators. Null/blank disables the fan-out entirely (the mirror still
  // posts):
  // the legacy 2-arg constructor leaves these unset so existing mirror-only wiring/tests are
  // unaffected, and production injects them via the @Autowired constructor below.
  private final WorkflowClient workflowClient;
  private final StrategyRegistry strategyRegistry;
  private final String triggerStrategyId;
  private final String coreTaskQueue;

  public WatchlistMirrorActivitiesImpl(
      WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this(webhookClient, webhookResolver, null, null, "", "orchestrator-core");
  }

  @Autowired
  public WatchlistMirrorActivitiesImpl(
      WebhookClient webhookClient,
      TenantWebhookResolver webhookResolver,
      WorkflowClient workflowClient,
      StrategyRegistry strategyRegistry,
      @Value("${watchlist.trigger.strategy-id:}") String triggerStrategyId,
      @Value("${temporal.task-queue:orchestrator-core}") String coreTaskQueue) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
    this.workflowClient = workflowClient;
    this.strategyRegistry = strategyRegistry;
    this.triggerStrategyId = triggerStrategyId == null ? "" : triggerStrategyId;
    this.coreTaskQueue = coreTaskQueue;
  }

  @Override
  public void postWatchlistAlert(WatchlistMirrorPayload payload) {
    ParseResult parsed = WatchlistParser.parse(payload.getRawText());
    // Per-(tenant, etDate) dedup: post the digest only for the fan-out entry that wins today's
    // marker. Gates the POST only — maybeStartTriggerSession below still runs unconditionally.
    if (shouldPostDigest(payload)) {
      String url = webhookResolver.resolve(payload.getTenantId(), payload.getStrategyId());
      if (parsed.clean() && !parsed.rows().isEmpty()) {
        webhookClient.postEmbedToUrl(url, buildEmbed(payload, parsed.rows()));
      } else {
        // Malformed/empty watchlist is never dropped — fall back to verbatim raw text.
        webhookClient.postToUrl(url, format(payload));
      }
    }
    // Additive, defensive ingest fan-out AFTER the mirror post (mirror output is byte-identical):
    // only on a clean parse, only for the configured trigger strategy, only when not explicitly
    // disabled. A start failure is caught + logged and never disrupts the mirror.
    maybeStartTriggerSession(payload, parsed);
  }

  /**
   * True iff THIS invocation should post the tenant's digest. When the fan-out client is not wired
   * (legacy 2-arg constructor / mirror-only tests) there is no dedup available, so it posts as
   * before. Otherwise it delegates to {@link #startDigestMarker}: {@code true} = this entry won the
   * per-{@code (tenant, etDate)} marker → post; {@code false} = another fan-out entry already
   * posted today → skip.
   */
  private boolean shouldPostDigest(WatchlistMirrorPayload payload) {
    if (workflowClient == null) {
      return true;
    }
    return startDigestMarker(payload.getTenantId(), payload.getEtDate().toString());
  }

  /**
   * Starts the {@code REJECT_DUPLICATE} digest marker keyed {@code t-{tenant}/wl/{etDate}/digest}.
   *
   * <p>Package-private (not private) so a unit test can drive the dedup decision via a spy: a real
   * {@link WorkflowClient#start} is a no-op against a mocked client and cannot surface {@code
   * REJECT_DUPLICATE} in a unit test.
   *
   * @return {@code true} if the marker start succeeded (this call owns today's digest post) or if
   *     the start failed for a non-dedup reason (fail-safe: a rare double beats a silent daily loss
   *     when Temporal itself is degraded); {@code false} on {@link WorkflowExecutionAlreadyStarted}
   *     (another fan-out entry already posted — skip).
   */
  boolean startDigestMarker(String tenantId, String etDate) {
    String workflowId = "t-" + tenantId + "/wl/" + etDate + "/digest";
    try {
      WatchlistDigestMarkerWorkflow marker =
          workflowClient.newWorkflowStub(
              WatchlistDigestMarkerWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(coreTaskQueue)
                  .setWorkflowId(workflowId)
                  .setWorkflowIdReusePolicy(
                      WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                  .build());
      WorkflowClient.start(marker::mark, tenantId, etDate);
      return true;
    } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
      // Another (tenant, strategy) fan-out entry already posted this tenant's digest today.
      return false;
    } catch (RuntimeException e) {
      // Fail-safe: a degraded Temporal must not silently drop the daily digest. Post anyway; a rare
      // cosmetic double beats a lost digest when the dedup mechanism itself is unavailable.
      log.warn(
          "watchlist digest dedup marker start failed; posting anyway tenant={} et_date={} err={}",
          tenantId,
          etDate,
          e.getMessage());
      return true;
    }
  }

  /**
   * Starts {@link WatchlistTriggerSessionWorkflow} when the parse is clean AND this strategy is the
   * configured trigger strategy AND it is not explicitly disabled. Best-effort: any failure (no
   * client wired, config miss, start error) is swallowed so the Discord mirror is never affected.
   */
  private void maybeStartTriggerSession(WatchlistMirrorPayload payload, ParseResult parsed) {
    if (workflowClient == null || triggerStrategyId.isBlank() || strategyRegistry == null) {
      return;
    }
    if (!parsed.clean() || parsed.rows().isEmpty()) {
      return;
    }
    if (!triggerStrategyId.equals(payload.getStrategyId())) {
      return;
    }
    try {
      StrategyConfig config = strategyRegistry.get(payload.getTenantId(), payload.getStrategyId());
      if (config == null || Boolean.FALSE.equals(config.getEnabled())) {
        return;
      }
      String workflowId =
          WorkflowIds.tenantStrategy(payload.getTenantId(), payload.getStrategyId())
              + "/wl/"
              + payload.getEtDate()
              + "/session";
      WatchlistTriggerSessionWorkflow session =
          workflowClient.newWorkflowStub(
              WatchlistTriggerSessionWorkflow.class,
              WorkflowOptions.newBuilder()
                  .setTaskQueue(coreTaskQueue)
                  .setWorkflowId(workflowId)
                  .setWorkflowIdReusePolicy(
                      WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                  .build());
      WorkflowClient.start(session::run, new WatchlistTriggerSessionWorkflowInput(payload, config));
    } catch (RuntimeException e) {
      // Includes WorkflowExecutionAlreadyStarted (same-day re-post idempotency) and any transport
      // error. The mirror has already posted; the fan-out is strictly additive.
      log.warn(
          "watchlist trigger session start skipped tenant={} strategy={} et_date={} err={}",
          payload.getTenantId(),
          payload.getStrategyId(),
          payload.getEtDate(),
          e.getMessage());
    }
  }

  /**
   * Builds the rich embed: green accent, date-stamped title, an {@code "via <author>"} footer, and
   * a per-play description (no code fence) so it uses the embed's full width.
   */
  private static WebhookEmbed buildEmbed(WatchlistMirrorPayload payload, List<TickerWatch> rows) {
    String title = "📋 Watchlist — " + EMBED_DATE.format(payload.getEtDate());
    String description = renderPlays(rows);
    String footer = "via " + payload.getAuthor();
    return new WebhookEmbed(title, description, DISCORD_GREEN, footer);
  }

  /**
   * Renders one line per leg (no code fence — the embed uses its full width): per ticker the call
   * line (if present) then the put line (if present), tickers in original order. A one-sided ticker
   * emits only that line — no placeholder. The play (ticker+strike) is bolded; the trigger is
   * plain.
   *
   * <pre>
   *   📈 **SPY 756C** — breaks above 755.30
   *   📉 **SPY 745P** — breaks below 748.00
   * </pre>
   */
  static String renderPlays(List<TickerWatch> rows) {
    StringBuilder sb = new StringBuilder();
    for (TickerWatch w : rows) {
      if (w.call() != null) {
        appendLine(sb, "📈", w.ticker(), w.call(), "breaks above");
      }
      if (w.put() != null) {
        appendLine(sb, "📉", w.ticker(), w.put(), "breaks below");
      }
    }
    return truncate(sb.toString(), EMBED_DESC_MAX, 0);
  }

  private static void appendLine(
      StringBuilder sb, String emoji, String ticker, Leg leg, String direction) {
    if (sb.length() > 0) {
      sb.append('\n');
    }
    sb.append(emoji)
        .append(" **")
        .append(ticker)
        .append(' ')
        .append(leg.strike())
        .append(Character.toUpperCase(leg.right()))
        .append("** — ")
        .append(direction)
        .append(' ')
        .append(leg.trigger().setScale(2, RoundingMode.HALF_UP).toPlainString());
  }

  /**
   * Pure, deterministic formatting: a header line, then the raw watchlist text wrapped in a
   * triple-backtick fenced code block. Neutralizes any literal triple-backtick in the body so it
   * cannot prematurely close the fence, and truncates the body (inside the fence) if the composed
   * message would exceed Discord's 2000-char limit.
   */
  static String format(WatchlistMirrorPayload payload) {
    String header =
        "📋 Watchlist — " + payload.getEtDate().toString() + " — via " + payload.getAuthor();

    String rawText = payload.getRawText() == null ? "" : payload.getRawText();
    // Fence-injection guard: neutralize literal fences by inserting a zero-width space so the body
    // can never close the surrounding code block early.
    String body = rawText.replace(FENCE, "``​`");

    // Overhead = header + "\n" + open-fence + "\n" + "\n" + close-fence.
    String prefix = header + "\n" + FENCE + "\n";
    String suffix = "\n" + FENCE;
    int overhead = prefix.length() + suffix.length();

    return prefix + truncate(body, DISCORD_MAX, overhead) + suffix;
  }

  /**
   * Truncates {@code body} (appending {@link #TRUNCATION_MARKER}) only when {@code overhead +
   * body.length()} would exceed {@code max}; otherwise returns {@code body} unchanged. The budget
   * is clamped non-negative so a pathological overhead never throws.
   */
  private static String truncate(String body, int max, int overhead) {
    if (overhead + body.length() <= max) {
      return body;
    }
    int budget = Math.max(0, max - overhead - TRUNCATION_MARKER.length());
    return body.substring(0, Math.min(body.length(), budget)) + TRUNCATION_MARKER;
  }
}
