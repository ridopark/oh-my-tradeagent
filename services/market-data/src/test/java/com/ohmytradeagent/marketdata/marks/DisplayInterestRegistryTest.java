package com.ohmytradeagent.marketdata.marks;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.marketdata.marks.DisplayInterestRegistry.Admission;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class DisplayInterestRegistryTest {

  private final MutableClock clock = new MutableClock(Instant.parse("2026-10-01T14:30:00Z"));
  private final DisplayInterestRegistry registry = new DisplayInterestRegistry(clock);

  @Test
  void admitsUpToTheCapThenCaps() {
    for (int i = 0; i < DisplayInterestRegistry.CAP; i++) {
      assertThat(registry.touch("OCC" + i)).isEqualTo(Admission.NEW);
    }
    assertThat(DisplayInterestRegistry.CAP).isEqualTo(25);
    assertThat(registry.touch("OCC-extra")).isEqualTo(Admission.CAPPED);
    // A known OCC is still refreshed while at the cap.
    assertThat(registry.touch("OCC0")).isEqualTo(Admission.EXISTING);
  }

  @Test
  void expiresOnlyAfterTheTtlSinceTheLastRequest() {
    registry.touch("A");
    clock.advance(Duration.ofSeconds(30));
    assertThat(registry.expireIfIdle("A")).isFalse();
    clock.advance(Duration.ofMillis(1));
    assertThat(registry.expireIfIdle("A")).isTrue();
    // Expiry frees the slot.
    assertThat(registry.touch("A")).isEqualTo(Admission.NEW);
  }

  @Test
  void anUnknownOccIsTreatedAsExpired() {
    assertThat(registry.expireIfIdle("never-seen")).isTrue();
  }
}
