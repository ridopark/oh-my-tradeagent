package com.ohmytradeagent.tdbff.optionschat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * This class is the SOLE gate on which Discord channels can be written to or read from the mirror.
 * Ingest rejects anything outside {@link OptionsChatChannels#allowed()}, and the read endpoint
 * resolves {@code ?channel=} through it — so a bug here either lets an arbitrary channel id into
 * the store, or serves rows from one the operator never configured.
 */
class OptionsChatChannelsTest {

  private static final long CHAT = 786109983065505792L;
  private static final long SIGNALS = 769797179992571914L;

  @Test
  void parsesIdsWithLabelsAndKeepsConfiguredOrder() {
    // Order is meaningful: the first entry is the tab the page opens on.
    OptionsChatChannels c = new OptionsChatChannels(CHAT + ":Discussion," + SIGNALS + ":Signals");

    assertThat(c.ordered())
        .containsExactly(
            new OptionsChatChannels.Channel(CHAT, "Discussion"),
            new OptionsChatChannels.Channel(SIGNALS, "Signals"));
    assertThat(c.defaultChannel()).isEqualTo(CHAT);
    assertThat(c.ids()).containsExactly(CHAT, SIGNALS);
  }

  @Test
  void aBareIdIsAllowedAndLabelsItself() {
    OptionsChatChannels c = new OptionsChatChannels(String.valueOf(CHAT));

    assertThat(c.ordered()).containsExactly(new OptionsChatChannels.Channel(CHAT, "" + CHAT));
  }

  @Test
  void toleratesWhitespaceAndEmptyEntries() {
    OptionsChatChannels c =
        new OptionsChatChannels("  " + CHAT + " : Discussion , , " + SIGNALS + " ");

    assertThat(c.ids()).containsExactly(CHAT, SIGNALS);
    assertThat(c.ordered().get(0).label()).isEqualTo("Discussion");
  }

  @Test
  void aRepeatedIdIsNotDuplicatedIntoTwoTabs() {
    OptionsChatChannels c = new OptionsChatChannels(CHAT + ":A," + CHAT + ":B");

    assertThat(c.ids()).containsExactly(CHAT);
    // First wins, so a duplicate cannot silently rename the tab.
    assertThat(c.ordered().get(0).label()).isEqualTo("A");
  }

  @Test
  void resolveAcceptsOnlyAllowedChannels_everythingElseFallsBackToTheDefault() {
    OptionsChatChannels c = new OptionsChatChannels(CHAT + ":Discussion," + SIGNALS + ":Signals");

    assertThat(c.resolve(String.valueOf(SIGNALS))).isEqualTo(SIGNALS);
    assertThat(c.resolve("  " + SIGNALS + " ")).isEqualTo(SIGNALS);

    // The whole point: a caller-supplied channel that is NOT configured must never reach a query.
    assertThat(c.resolve("999999999999999999")).isEqualTo(CHAT);
    assertThat(c.resolve("not-a-number")).isEqualTo(CHAT);
    assertThat(c.resolve("")).isEqualTo(CHAT);
    assertThat(c.resolve(null)).isEqualTo(CHAT);
    assertThat(c.resolve("786109983065505792; DROP TABLE options_chat_message")).isEqualTo(CHAT);
  }

  @Test
  void allowedIsMembershipNotAPrefixOrRange() {
    OptionsChatChannels c = new OptionsChatChannels(String.valueOf(CHAT));

    assertThat(c.allowed()).containsExactly(CHAT);
    // A near-miss id must not be admitted by any accidental prefix/range logic.
    assertThat(c.allowed()).doesNotContain(CHAT + 1, CHAT / 10, 7861099830655057L);
  }

  @Test
  void misconfigurationFailsAtBoot_notOnEveryRequestForever() {
    // Failing loudly at startup beats 400-ing every ingest for the life of the deployment, where it
    // would look like a scraper bug rather than a typo in config.
    assertThatThrownBy(() -> new OptionsChatChannels("not-a-number"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("non-numeric");
    assertThatThrownBy(() -> new OptionsChatChannels(""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least one");
    assertThatThrownBy(() -> new OptionsChatChannels(" , , "))
        .isInstanceOf(IllegalStateException.class);
  }
}
