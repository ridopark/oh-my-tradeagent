"""Sidecar entrypoint. Wires env-config into Watcher + TemporalEmitter."""

from __future__ import annotations

import asyncio
import logging
import os
import pathlib

from dotenv import load_dotenv
from playwright.async_api import async_playwright

from .emitter import TemporalDeriskEmitter, TemporalEmitter, TemporalWatchlistEmitter
from .runtime import required, setup_logging
from .watcher import Watcher
from .watchlist_watcher import WatchlistWatcher


# Kept as module-local aliases so every existing call site (and test) is unchanged; the bodies now
# live in runtime.py so chat_main.py can share them without importing this module (which would pull
# in temporalio).
_setup_logging = setup_logging
_required = required


def _parse_additional_targets(raw: str) -> list[tuple[str, str]]:
    """Parse ``SIGNAL_EMIT_ADDITIONAL_TARGETS`` (``tenant:strategy,tenant:strategy``) into a list of
    extra fan-out targets. Empty → no extras (single-tenant, unchanged). One browser/Discord session
    can thus feed several tenants on the same channel (e.g. a live tenant + a paper shadow).

    NOTE: despite the ``SIGNAL_EMIT_`` name, this list drives BOTH per-tenant fan-outs — trading
    signals (``Watcher``) AND the daily watchlist mirror (``WatchlistWatcher``) — kept in lockstep
    on purpose so a shadow tenant never gets one without the other. Editing it moves both."""
    targets: list[tuple[str, str]] = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        tenant, _, strategy = item.partition(":")
        tenant, strategy = tenant.strip(), strategy.strip()
        if not tenant or not strategy:
            raise SystemExit(
                f"SIGNAL_EMIT_ADDITIONAL_TARGETS entry '{item}' must be 'tenant:strategy'"
            )
        targets.append((tenant, strategy))
    return targets


def _watchlist_targets(signal_targets: list[tuple[str, str]]) -> list[tuple[str, str]]:
    """Fan-out targets for the watchlist mirror.

    Decoupled from the signal fan-out: WATCHLIST_MIRROR_ADDITIONAL_TARGETS lets the
    per-tenant digest be scoped independently of the per-(tenant,strategy) signal list
    (where a tenant can legitimately appear twice). Unset (env absent) -> fall back to the
    signal targets for back-compat (deployments that don't set it are unchanged). Set-but-
    empty ("") -> empty list (explicit opt-out of any extra digest fan-out).
    """
    raw = os.getenv("WATCHLIST_MIRROR_ADDITIONAL_TARGETS")
    if raw is None:
        return signal_targets
    return _parse_additional_targets(raw)


def _log_if_failed(log: logging.Logger, name: str):
    """Done-callback that logs a non-cancellation task failure. Used to isolate
    the best-effort watchlist watcher so its crash never propagates."""

    def _cb(task: "asyncio.Task[None]") -> None:
        if task.cancelled():
            return
        exc = task.exception()
        if exc is not None:
            log.error("%s ended with error: %r", name, exc)

    return _cb


async def _amain() -> None:
    load_dotenv()
    log = _setup_logging(os.getenv("LOG_LEVEL", "info"))

    channel_url = _required("DISCORD_CHANNEL_URL")
    tenant_id = _required("TENANT_ID")
    strategy_id = _required("STRATEGY_ID")
    additional_targets = _parse_additional_targets(
        os.getenv("SIGNAL_EMIT_ADDITIONAL_TARGETS", "")
    )
    # Phase B2: source of the signal fan-out targets. Default "env" is
    # byte-identical to today (parse SIGNAL_EMIT_ADDITIONAL_TARGETS). "registry"
    # derives them from api-gateway's B1 endpoint on a refresh interval. The
    # watchlist mirror keeps its own env list either way (out of scope).
    fanout_source = os.getenv("SIGNAL_FANOUT_SOURCE", "env").strip().lower()
    if fanout_source not in ("env", "registry"):
        raise SystemExit("SIGNAL_FANOUT_SOURCE must be 'env' or 'registry'")
    # Phase 2: the watchlist mirror's fan-out source, independent of the signal one.
    watchlist_fanout_source = os.getenv("WATCHLIST_FANOUT_SOURCE", "env").strip().lower()
    if watchlist_fanout_source not in ("env", "registry"):
        raise SystemExit("WATCHLIST_FANOUT_SOURCE must be 'env' or 'registry'")
    # In registry mode the watcher starts primary-only (never empty) and the
    # refresher populates it; in env mode it gets the parsed env list as today.
    signal_additional_targets = [] if fanout_source == "registry" else additional_targets
    temporal_target = os.getenv("TEMPORAL_TARGET", "localhost:7233")
    temporal_namespace = os.getenv("TEMPORAL_NAMESPACE", "default")
    task_queue = os.getenv("TEMPORAL_TASK_QUEUE", "orchestrator-core")
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "./state"))
    poll_interval = float(os.getenv("POLL_INTERVAL_SECS", "1.0"))

    watchlist_enabled = (
        os.getenv("WATCHLIST_MIRROR_ENABLED", "false").strip().lower() == "true"
    )
    watchlist_channel_url = os.getenv("DISCORD_WATCHLIST_CHANNEL_URL", "").strip()
    if watchlist_enabled and not watchlist_channel_url:
        # Enabled but no channel configured (e.g. the optional sidecar-config
        # secret key is absent → empty string). Degrade gracefully rather than
        # crash: a missing watchlist URL must never take down the trading-critical
        # signal sidecar. The mirror simply stays off until the URL is provided.
        log.warning(
            "WATCHLIST_MIRROR_ENABLED=true but DISCORD_WATCHLIST_CHANNEL_URL is "
            "unset — watchlist mirror disabled"
        )
        watchlist_enabled = False
    if watchlist_enabled:
        watchlist_poll_interval = float(os.getenv("WATCHLIST_POLL_INTERVAL_SECS", "45"))
        watchlist_author = os.getenv("WATCHLIST_AUTHOR", "TradingTheTrend")

    state_dir.mkdir(parents=True, exist_ok=True)

    # Fail fast on a missing Discord session BEFORE dialing Temporal, so we never
    # leave a connected emitter unclosed (its close() lives in the finally below).
    storage_state_path = state_dir / "storage_state.json"
    if not storage_state_path.exists():
        raise RuntimeError(
            f"storage_state.json missing at {storage_state_path} "
            "— run bootstrap first (see README)"
        )

    # Phase B2: in registry mode, validate the api-gateway config BEFORE dialing
    # Temporal — a missing var must fail fast without leaving a connected emitter
    # unclosed (same discipline as the storage_state check above).
    gw_base_url = None
    gw_token = None
    if fanout_source == "registry" or watchlist_fanout_source == "registry":
        gw_base_url = _required("API_GATEWAY_BASE_URL")
        gw_token = _required("API_GATEWAY_SHARED_TOKEN")

    # STC close-intent enrichment (Phase 2, dark by default). Built BEFORE dialing Temporal so an
    # enabled-but-unconfigured classifier (missing STC_INTENT_URL) fails fast WITHOUT leaving a
    # connected emitter unclosed — same discipline as the storage_state + gateway-config checks
    # above. Built ONLY when enabled; the lazy import keeps disabled deployments from constructing
    # the httpx client. Passed into the Watcher; None => disabled, signals emit exactly as today.
    # min_confidence defaults to 0.0 so shadow mode captures EVERY classification for evaluation —
    # operators raise the floor when a later phase enforces per-tenant.
    stc_intent_enabled = (
        os.getenv("STC_INTENT_ENRICH_ENABLED", "false").strip().lower() == "true"
    )
    intent_classifier = None
    if stc_intent_enabled:
        from .stc_intent import StcIntentClassifier

        intent_classifier = StcIntentClassifier(
            url=_required("STC_INTENT_URL"),
            timeout_ms=float(os.getenv("STC_INTENT_TIMEOUT_MS", "300")),
            min_confidence=float(os.getenv("STC_INTENT_MIN_CONFIDENCE", "0.0")),
            log=log,
        )

    log.info(
        "starting sidecar (tenant=%s strategy=%s additional_targets=%s target=%s task_queue=%s)",
        tenant_id,
        strategy_id,
        additional_targets,
        temporal_target,
        task_queue,
    )
    emitter = await TemporalEmitter.connect(
        target=temporal_target, namespace=temporal_namespace, task_queue=task_queue
    )

    # De-risk-on-follow-up-cue (PLAN-2026-08-04, dark by default). When enabled, a "0-or-hero" /
    # "use-your-own-stop" message following a BTO starts a CopytradeDeriskWorkflow that trims + arms
    # the attributed open position. Reuses the SAME connected Temporal client + task queue (no second
    # dial), like the watchlist emitter. None => disabled: non-grammar messages are ignored exactly
    # as today, and no per-author BTO history is kept.
    derisk_enabled = (
        os.getenv("DERISK_CUE_ENABLED", "false").strip().lower() == "true"
    )
    derisk_emitter = (
        TemporalDeriskEmitter(emitter.client, emitter.task_queue)
        if derisk_enabled
        else None
    )
    if derisk_enabled:
        log.info("de-risk-on-follow-up-cue enabled (workflow=CopytradeDeriskWorkflow)")

    watcher = Watcher(
        channel_url=channel_url,
        state_dir=state_dir,
        emitter=emitter,
        tenant_id=tenant_id,
        strategy_id=strategy_id,
        additional_targets=signal_additional_targets,
        log=log,
        poll_interval_secs=poll_interval,
        intent_classifier=intent_classifier,
        derisk_emitter=derisk_emitter,
    )

    # Phase B2 registry refresher — built only in registry mode, isolated in its
    # own module, non-fatal to signal emission. Imported lazily so env mode
    # never touches httpx.
    fanout_refresher = None
    if fanout_source == "registry":
        from .fanout_registry import FanoutRefresher, FanoutRegistryClient

        refresh_secs = float(os.getenv("SIGNAL_FANOUT_REFRESH_SECS", "60"))
        fanout_refresher = FanoutRefresher(
            client=FanoutRegistryClient(base_url=gw_base_url, token=gw_token),
            apply_targets=watcher.update_targets,
            log=log,
            refresh_secs=refresh_secs,
        )
        log.info(
            "signal fan-out source=registry (endpoint=%s refresh=%ss)",
            gw_base_url,
            refresh_secs,
        )

    watchlist_watcher: WatchlistWatcher | None = None
    if watchlist_enabled:
        # Reuse the SAME connected Temporal client + task queue — no second dial.
        watchlist_emitter = TemporalWatchlistEmitter(emitter.client, emitter.task_queue)
        watchlist_watcher = WatchlistWatcher(
            channel_url=watchlist_channel_url,
            state_dir=state_dir,
            emitter=watchlist_emitter,
            tenant_id=tenant_id,
            strategy_id=strategy_id,
            author=watchlist_author,
            log=log,
            poll_interval_secs=watchlist_poll_interval,
            # Mirror the daily watchlist to WATCHLIST_MIRROR_ADDITIONAL_TARGETS (its own
            # fan-out var, scoped independently of the per-(tenant,strategy) signal list where
            # a tenant may appear twice), falling back to the signal targets when that var is
            # unset — so each tenant's digest lands in its own channel, once.
            additional_targets=_watchlist_targets(additional_targets),
        )
        log.info("watchlist mirror enabled (channel=%s author=%s)",
                 watchlist_channel_url, watchlist_author)

    # Phase 2 (watchlist fan-out DB-driven): a SECOND registry refresher, pointed at the watchlist
    # endpoint + the watchlist watcher's update_targets, so an enabled watchlist strategy_config row
    # routes without editing WATCHLIST_MIRROR_ADDITIONAL_TARGETS + restarting. Independent of the
    # signal fan-out source (its own WATCHLIST_FANOUT_SOURCE flag); env mode is unchanged. Isolated +
    # non-fatal like the signal refresher — the last good set (initially the env list) survives a
    # failed poll.
    watchlist_fanout_refresher = None
    if watchlist_watcher is not None and watchlist_fanout_source == "registry":
        from .fanout_registry import (
            WATCHLIST_FANOUT_TARGETS_PATH,
            FanoutRefresher,
            FanoutRegistryClient,
        )

        wl_refresh_secs = float(os.getenv("WATCHLIST_FANOUT_REFRESH_SECS", "60"))
        watchlist_fanout_refresher = FanoutRefresher(
            client=FanoutRegistryClient(
                base_url=gw_base_url, token=gw_token, path=WATCHLIST_FANOUT_TARGETS_PATH
            ),
            apply_targets=watchlist_watcher.update_targets,
            log=log,
            refresh_secs=wl_refresh_secs,
            # An empty watchlist registry is legitimate (no tenants opted in — the first rollout
            # step), so apply it as a normal poll instead of ERROR-spamming a false failure.
            allow_empty=True,
        )
        log.info(
            "watchlist fan-out source=registry (endpoint=%s refresh=%ss)",
            gw_base_url,
            wl_refresh_secs,
        )

    try:
        # ONE browser + context shared by both watchers — each gets its own
        # page (tab). A second Chromium would roughly double memory and OOM the
        # homelab sidecar's 1Gi limit (see PLAN-watchlist-mirror).
        async with async_playwright() as pw:
            # Memory-hardening for the 2-tab Discord Chromium in a limited container
            # (see 55-signal-source-discord.yaml's 2Gi limit). Discord is a heavy SPA
            # and two tabs intermittently OOMKilled the old 1Gi pod:
            #   --disable-dev-shm-usage: write shared memory to /tmp, not the default
            #       64Mi /dev/shm tmpfs Chromium otherwise exhausts under Discord.
            #   --disable-gpu: headless has no GPU compositor to feed.
            #   --js-flags=--max-old-space-size: cap each tab's V8 old-space heap so a
            #       single tab cannot balloon unbounded.
            browser = await pw.chromium.launch(
                headless=True,
                args=[
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    "--js-flags=--max-old-space-size=384",
                ],
            )
            context = await browser.new_context(storage_state=str(storage_state_path))

            signal_page = await context.new_page()
            signal_task = asyncio.create_task(
                watcher.run_on_page(signal_page), name="signal-watcher"
            )

            fanout_task: asyncio.Task[None] | None = None
            if fanout_refresher is not None:
                # ISOLATION: the registry refresher is best-effort and MUST NOT
                # take down the trading-critical signal watcher. Run it as a
                # sibling task with a log-only done-callback; a crash here leaves
                # the last good fan-out set in place.
                fanout_task = asyncio.create_task(
                    fanout_refresher.run(), name="fanout-refresher"
                )
                fanout_task.add_done_callback(_log_if_failed(log, "fan-out refresher"))

            watchlist_task: asyncio.Task[None] | None = None
            if watchlist_watcher is not None:
                # The watcher owns its own watchlist tab (creates it from the
                # shared context) so it can rebuild the tab after a renderer
                # crash without touching the signal tab.
                watchlist_task = asyncio.create_task(
                    watchlist_watcher.run_on_context(context), name="watchlist-watcher"
                )
                # ISOLATION: the watchlist watcher is best-effort. If it dies, log
                # it — never let it take down the process.
                watchlist_task.add_done_callback(_log_if_failed(log, "watchlist watcher"))

            watchlist_fanout_task: asyncio.Task[None] | None = None
            if watchlist_fanout_refresher is not None:
                # ISOLATION: best-effort sibling, same as the signal fan-out refresher — a crash
                # here leaves the last good watchlist fan-out set in place, never touches the
                # trading-critical signal watcher.
                watchlist_fanout_task = asyncio.create_task(
                    watchlist_fanout_refresher.run(), name="watchlist-fanout-refresher"
                )
                watchlist_fanout_task.add_done_callback(
                    _log_if_failed(log, "watchlist fan-out refresher")
                )

            try:
                # The signal watcher is trading-critical: await IT directly so a
                # crash propagates immediately and the process exits non-zero for
                # k8s to restart. We do NOT gather() over both — gather waits for
                # ALL tasks, and the forever-running watchlist task would
                # otherwise mask a signal-watcher crash indefinitely.
                await signal_task
            finally:
                for task in (watchlist_task, fanout_task, watchlist_fanout_task):
                    if task is not None:
                        task.cancel()
                        try:
                            await task
                        except asyncio.CancelledError:
                            pass
    finally:
        await emitter.close()
        if intent_classifier is not None:
            await intent_classifier.aclose()


def main() -> None:
    asyncio.run(_amain())


if __name__ == "__main__":
    main()
