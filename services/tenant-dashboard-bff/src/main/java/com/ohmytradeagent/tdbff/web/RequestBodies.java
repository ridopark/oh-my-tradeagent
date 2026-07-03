package com.ohmytradeagent.tdbff.web;

import java.util.Map;

/**
 * Tiny shared reader for the {@code @RequestBody Map<String, Object>} JSON bodies the write
 * controllers accept. Most BFF controllers only BUILD response maps; the invite/bind endpoints are
 * the first to READ a JSON body, so this one-liner is centralized here rather than copied per
 * controller.
 */
final class RequestBodies {

  private RequestBodies() {}

  /** The string value at {@code key}, or {@code null} if the body or the value is absent. */
  static String str(Map<String, Object> body, String key) {
    if (body == null) {
      return null;
    }
    Object v = body.get(key);
    return v == null ? null : v.toString();
  }
}
