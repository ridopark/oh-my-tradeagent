package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ScaleInMatcherTest {

  @ParameterizedTest
  @ValueSource(strings = {"scaling in", "scale in", "starter", "small size", "half size"})
  void eachCuePhrase_matches(String phrase) {
    assertThat(ScaleInMatcher.match(phrase)).contains(phrase);
  }

  @Test
  void caseInsensitive_matchesAndReportsLowerCasedPhrase() {
    assertThat(ScaleInMatcher.match("Scaling In on this")).contains("scaling in");
  }

  @Test
  void incidentTail_riskyScalingIn_matchesScalingIn() {
    // Live incident: "BTO SPY 8/04 725p @ 3.09 risky, scaling in." — the tail after the grammar is
    // "risky, scaling in" and must resolve to the "scaling in" cue (NOT "risky", which is not a
    // cue).
    assertThat(ScaleInMatcher.match("risky, scaling in")).contains("scaling in");
  }

  @ParameterizedTest
  @ValueSource(strings = {"risky", "taking a shot", "adding here", "full send"})
  void nonCueTail_doesNotMatch(String tail) {
    assertThat(ScaleInMatcher.match(tail)).isEmpty();
  }

  @Test
  void nullTail_doesNotMatch() {
    assertThat(ScaleInMatcher.match(null)).isEmpty();
  }

  @Test
  void blankTail_doesNotMatch() {
    assertThat(ScaleInMatcher.match("   ")).isEmpty();
    assertThat(ScaleInMatcher.match("")).isEmpty();
  }

  @Test
  void firstCuePhraseInVocabularyOrderWins() {
    // "scaling in" precedes "starter" in the phrase list; a tail with both reports the first.
    Optional<String> m = ScaleInMatcher.match("starter, scaling in");
    assertThat(m).contains("scaling in");
  }
}
