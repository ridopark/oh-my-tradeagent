package com.ohmytradeagent.tdbff.web;

import static org.assertj.core.api.Assertions.assertThat;

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
}
