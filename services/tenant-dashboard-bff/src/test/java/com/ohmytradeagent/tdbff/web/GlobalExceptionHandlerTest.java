package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.tdbff.platform.YamlStrategyRegistry.StrategyNotFoundException;
import java.time.format.DateTimeParseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void malformedTimestampIs400NotA500() {
    // OffsetDateTime.parse on a bad `since` throws this; it must surface as a client error.
    var ex = new DateTimeParseException("Text 'nope' could not be parsed", "nope", 0);

    var response = handler.badTimestamp(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsEntry("error", "bad_request");
  }

  @Test
  void strategyNotFoundIs404_withNoFilesystemPathInBody() {
    var ex =
        new StrategyNotFoundException("Strategy YAML not found at /etc/copytrade/tenants/x.yaml");

    var response = handler.strategyNotFound(ex);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    assertThat(response.getBody())
        .containsExactlyInAnyOrderEntriesOf(
            java.util.Map.of("error", "strategy_not_configured")); // no path/detail leaked
  }
}
