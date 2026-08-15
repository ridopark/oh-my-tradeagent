package com.ohmytradeagent.tdbff.optionschat;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The Discord channels this mirror will accept and serve.
 *
 * <p>ONE definition shared by the ingest and both read paths. Parsing the property separately in
 * each controller would let them disagree — and the failure would be silent and asymmetric: a
 * channel the ingest accepts but the read never serves stores rows nobody can see, while the
 * reverse serves a channel nothing fills.
 *
 * <p>ORDER IS MEANINGFUL. The first entry is the default the page opens on, so a {@link
 * LinkedHashSet} rather than a plain set — reordering the property reorders the tabs.
 */
@Component
@ConditionalOnProperty(
    name = {"options-chat.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class OptionsChatChannels {

  /** One mirrored channel: its snowflake, and the tab label the page shows. */
  public record Channel(long id, String label) {}

  private final List<Channel> ordered;
  private final Set<Long> allowed;

  public OptionsChatChannels(@Value("${options-chat.channel-ids}") String raw) {
    // Entries are `id` or `id:Label`. The label lives HERE rather than in the page so adding a
    // channel is a config change on one side only — the page renders whatever it is told and has
    // no channel ids compiled into it.
    List<Channel> parsed = new ArrayList<>();
    Set<Long> ids = new LinkedHashSet<>();
    for (String part : raw.split(",")) {
      String trimmed = part.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      String[] bits = trimmed.split(":", 2);
      long id;
      try {
        id = Long.parseLong(bits[0].trim());
      } catch (NumberFormatException e) {
        // Fail at BOOT rather than 400 every ingest for the life of the deployment.
        throw new IllegalStateException(
            "options-chat.channel-ids contains a non-numeric id: " + bits[0], e);
      }
      String label = bits.length > 1 && !bits[1].isBlank() ? bits[1].trim() : bits[0].trim();
      if (ids.add(id)) {
        parsed.add(new Channel(id, label));
      }
    }
    if (parsed.isEmpty()) {
      throw new IllegalStateException("options-chat.channel-ids must list at least one channel");
    }
    this.ordered = List.copyOf(parsed);
    this.allowed = Set.copyOf(ids);
  }

  /** Membership test for ingest. An allowlist — never a range or a prefix. */
  public Set<Long> allowed() {
    return allowed;
  }

  /** Channels in configured order; the first is the page's default. */
  public List<Channel> ordered() {
    return ordered;
  }

  /** Just the ids, for queries that span every mirrored channel. */
  public List<Long> ids() {
    return ordered.stream().map(Channel::id).toList();
  }

  public long defaultChannel() {
    return ordered.get(0).id();
  }

  /** The requested channel if it is allowed, otherwise the default — never a caller-chosen id. */
  public long resolve(String requested) {
    if (requested != null && !requested.isBlank()) {
      try {
        long asked = Long.parseLong(requested.trim());
        if (allowed.contains(asked)) {
          return asked;
        }
      } catch (NumberFormatException e) {
        // fall through to the default
      }
    }
    return defaultChannel();
  }
}
