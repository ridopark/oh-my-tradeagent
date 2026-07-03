package com.ohmytradeagent.apigateway.web;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /admin/tenants/{tenant}/delete}: the type-to-confirm token. {@code
 * confirm_tenant_id} must string-equal the path {@code {tenant}} (case-sensitive) or the delete is
 * refused 400 {@code CONFIRM_MISMATCH} before any side effect.
 */
public record TenantDeleteRequestBody(@JsonProperty("confirm_tenant_id") String confirmTenantId) {}
