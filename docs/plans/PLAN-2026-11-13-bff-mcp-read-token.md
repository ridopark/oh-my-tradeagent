# PLAN — 2026-11-13 bff-mcp-read-token

PRD: issue #1053, **step 1 only** (BFF MCP read token + `whoami`). The MCP server (PRD step 2) and the
client/runbook docs (step 3) are not planned here.

Branch `feat/bff-mcp-read-token`, base `48bf60e8`. Module: `services/tenant-dashboard-bff` only, plus
one env entry in `infra/k8s/58-tenant-dashboard-bff.yaml` and one key in
`infra/secrets-template/secrets.template.yaml`.

## Goal

The BFF accepts a third credential class, the **MCP read token**, whose tenant is bound server-side
(`sha256(token) → tenant_id` in config). A request carrying one can only `GET`/`HEAD` an explicit
allowlist of tenant-scoped reads and shared market-data reads, always as its bound tenant; everything
else is refused by the auth filter before any handler runs. A new `GET /api/mcp/whoami` tells the
holder which tenant it is bound to.

**Done when:**

- `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS`; `ServiceTokenFilterTest`
  runs the 46 existing tests plus the new ones listed under Step 1, all green; the new
  `McpWhoamiController*` tests under Step 2 are green; `ApplicationContextSmokeTest` still loads.
- With `bff.mcp-read-tokens=87121ec9ab39dfe5f0915c4b2b25f2592e80aebba9d1ee1a081caf28491478fc:acme`
  (that is `sha256("test-mcp-token-acme")`), a filters-ON MockMvc request
  `GET /api/mcp/whoami` + `Authorization: Bearer test-mcp-token-acme` → 200
  `{"tenant_id":"acme","token_id":"87121ec9ab39","scope":"read"}`; the same with the shared token →
  403; the same with `X-Tenant-Id: beta` → 403; `POST /api/mcp/whoami` with the MCP token → 403.
- With the MCP token: `GET /api/positions` passes the filter and the downstream request reports
  `X-Tenant-Id = acme` and no `X-Operator-Id`; `POST /api/positions/force-close`,
  `POST /api/positions/partial-close`, `POST /api/entries/manual`, `POST /api/account-killswitch/reset`
  → 403; `GET /api/admin/tenants`, `GET /api/broker-credentials/status`,
  `GET /api/options-chat/messages`, `GET /internal/options-chat/messages`,
  `GET /internal/flow/watchlist` → 403 or 401 as specified in Step 1; unknown token → 401 with body
  `{"error":"unauthorized"}`.
- `new ServiceTokenFilter(shared, ingest, "nothex:acme", prodEnv)` throws `IllegalStateException`;
  the empty string constructs fine and accepts no MCP token.
- Every guard listed in Step 1's mutation table was seen red once (evidence recorded in the step's
  checklist), then restored green.
- `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:check` passes.
- Deploying the manifest change is behaviour-neutral: the new env var resolves to empty (secret key
  absent, `optional: true`), so no MCP token is accepted until the operator provisions one.

## Non-goals

- No MCP server, no Python, no `TenantResolver`, no tools or prompt (PRD step 2).
- No runbook / client-setup docs beyond code comments and the secrets-template comment (PRD step 3).
- No new BFF reads other than `whoami`. In particular no strategy-config read for the MCP scope even
  though `get_portfolio_summary` will want `notional_cap_pct_of_capital_base` (see Open questions).
- No change to shared-token or ingest-token behaviour, to `TenantContext`, to
  `GlobalExceptionHandler`, or to any existing handler.
- No remote transport, OAuth, NetworkPolicy, ingress or port-forward work (A2 / step 2).
- No minting of real tokens; no `kubectl` against the homelab.

## Spec

### Problem Statement

The BFF trusts one shared bearer token; whoever holds it can act as any tenant and call real-money
write routes. The operator wants to hand an external AI a credential that can read exactly one
tenant's positions and nothing else, so that a mis-pasted config, a hostile client or a prompt
injection can never widen access or trade.

### Solution

A per-tenant, read-only credential recognised by the existing auth filter. The tenant is a property
of the credential on the server; the client's view of "which tenant" (its config, its `X-Tenant-Id`)
is only ever checked against it. The holder can ask the BFF `whoami` and the answer is the bound
tenant.

### User Stories

PRD #1053 stories 4–14 and 30 are the ones this step satisfies:

4. Each read-only token is bound to exactly one tenant on the server, so editing a client config
   can never widen access.
5. A token can only read, so no model mistake can trade, trim, force-close or reset the kill switch.
6. Write routes return 403 for an MCP token even when otherwise well-formed.
7. An MCP token with a forged `X-Tenant-Id` for another tenant is rejected.
8. An unknown or revoked token gets 401, indistinguishable from a bad token.
9. Mint a token for a tenant / revoke it by removing its hash — no code change.
10. Only token hashes are stored in the cluster.
11. MCP tokens cannot reach admin reads (tenant list, invites, broker credentials).
12. MCP tokens cannot reach the sidecar ingest routes.
13. Every MCP read is logged with tenant + token id (never the token).
14. `whoami` returns the token's tenant.
30. The read-only scope is enforced in the BFF's existing auth filter: one choke point.

### Implementation Decisions

- **Config property** `bff.mcp-read-tokens` (env `BFF_MCP_READ_TOKENS`, default empty), a
  comma-separated list of `<sha256-hex>:<tenant_id>` entries. Entries are trimmed; blank entries are
  skipped (a trailing comma is harmless); any other entry that does not match
  `^[0-9a-fA-F]{64}:[A-Za-z0-9_-]+$` is malformed. Hex is normalised to lower case. The colon is a
  safe separator because neither hex nor the tenant charset contains one.
- **Boot check.** A malformed entry throws `IllegalStateException` from the filter constructor. The
  PRD requires this under the `prod` profile; the plan makes it unconditional (a malformed map has
  no legitimate dev use, unlike the well-known default shared token). The prod-profile test the PRD
  asks for still passes. See Open questions.
- **Constructor.** `ServiceTokenFilter` keeps exactly ONE constructor, extended with
  `@Value("${bff.mcp-read-tokens:}") String mcpReadTokens` as the third parameter (before
  `Environment`). The existing test call sites (`new ServiceTokenFilter(TOKEN, INGEST_TOKEN, env)`,
  7 places) are updated mechanically to pass `""`. No second constructor (Spring 2-ctor trap;
  `SpringComponentConstructorGuardTest` would also flag it).
- **Matching.** Compute `sha256(presented)` once per request; compare its bytes against EVERY
  configured hash with `MessageDigest.isEqual`, remembering the match and not short-circuiting. The
  map is tiny, and the loop shape means timing is independent of which entry (if any) matches.
- **Decision order in `doFilterInternal`** (first match wins):
  1. `hasSuspiciousPath` → 401 (unchanged, still first).
  2. No `Bearer ` header → 401 (unchanged).
  3. Ingest prefix → only the ingest token (unchanged). An MCP token here is therefore a 401, with
     no special case: `expectedTokenFor` still returns the ingest token for these paths.
  4. Presented == shared token → pass through unchanged (no attribute set, headers untouched).
  5. Presented hashes to a configured MCP entry →
     - method not `GET`/`HEAD` → 403;
     - raw `getRequestURI()` not an exact member of the allowlist → 403;
     - caller `X-Tenant-Id` present, non-blank and ≠ bound tenant → 403;
     - else: wrap the request, set the token-id request attribute, log, `chain.doFilter(wrapped)`.
  6. Otherwise → 401 (same body as today, `{"error":"unauthorized"}`).
- **403 body** is `{"error":"forbidden"}`, `application/json` — the same shape
  `GlobalExceptionHandler` uses for a non-allowlisted operator.
- **Allowlist** (exact raw-path match, no trailing slash, no path variables; the firewall guard
  already rejects every path that could normalise into one of these). Derived from the controllers'
  class + method mappings:

  | Path | Controller |
  | --- | --- |
  | `/api/positions` | `PositionsController` |
  | `/api/portfolio` | `PortfolioController` |
  | `/api/portfolio-history` | `PortfolioHistoryController` |
  | `/api/live/marks` | `LiveMarksController` |
  | `/api/trades` | `TradesController` |
  | `/api/orders` | `OrdersController` |
  | `/api/signals` | `SignalsController` |
  | `/api/trail-liveness` | `TrailLivenessController` |
  | `/api/floor-breach` | `FloorBreachController` |
  | `/api/account-killswitch` | `AccountKillSwitchController` (GET state only) |
  | `/api/entries/quote` | `ManualEntryController` |
  | `/api/entries/chain` | `ManualEntryController` |
  | `/api/entries/chain/expirations` | `ManualEntryController` |
  | `/api/flow/orders` | `FlowController` |
  | `/api/flow/summary` | `FlowController` |
  | `/api/darkpool/prints` | `DarkpoolController` |
  | `/api/darkpool/summary` | `DarkpoolController` |
  | `/api/mcp/whoami` | new `McpWhoamiController` |

  Deliberately NOT allowlisted (all 403 for an MCP token): `/api/positions/arm-anchors`,
  `/api/positions/adopt`, `/api/entries/watchlist`, `/api/entries/{signalId}/status`,
  `/api/entries/manual`, `/api/live/connection`, `/api/live-promotion`, `/api/proximity`,
  `/api/options-chat/**` (incl. `/api/options-chat/media/{id}`), `/api/broker-credentials/status`,
  `/api/tenant-config`, `/api/strategy-config`, `/api/admin/**`, and every `/internal/**` (the
  ingest prefixes are 401 by rule 3; `/internal/provisioning/bind` is 403 by the allowlist).
  Note that `/api/entries/quote|chain|chain/expirations` additionally 404 when manual entry is
  disabled or the tenant is not on its allowlist; that is the handler's existing behaviour and is
  not changed here.
- **Tenant injection** via a private static `HttpServletRequestWrapper` nested in the filter that
  overrides `getHeader`, `getHeaders` and `getHeaderNames` (case-insensitive names): `X-Tenant-Id`
  reads as the bound tenant, `X-Operator-Id` reads as absent. Handlers keep resolving the tenant
  through `TenantContext.tenantId(req)`; no handler changes.
- **Token id** = the first 12 hex characters of the sha256 hex. Exposed to `whoami` through a
  request attribute whose name is a `public static final String` on `ServiceTokenFilter`
  (`MCP_TOKEN_ID_ATTRIBUTE`). Attributes are server-side only, so no caller can forge one.
- **Logging.** One `INFO` line per MCP-authorised request:
  `mcp read tenant={} token_id={} method={} path={}`. Never the token, never the full hash. The
  filter gains an slf4j `Logger` (as `GlobalExceptionHandler` has).
- **`GET /api/mcp/whoami`** in a new `web/McpWhoamiController` (one constructor taking
  `TenantContext`): if the token-id attribute is absent → 403 `{"error":"forbidden"}`; else 200
  `{"tenant_id": ctx.tenantId(req), "token_id": <attr>, "scope": "read"}` (insertion-ordered map).
  The shared token reaches the handler (rule 4) and gets the 403 there, because it has no single
  tenant. POST/PUT/DELETE never reach it with an MCP token (rule 5) and 405 with the shared token as
  any unmapped method does today.
- **Wiring (behaviour-neutral).** `application.yml`: `bff.mcp-read-tokens: ${BFF_MCP_READ_TOKENS:}`
  with a comment stating the format, the fail-closed default and how to mint
  (`openssl rand -hex 32` → token; `printf %s "$token" | sha256sum` → hash).
  `58-tenant-dashboard-bff.yaml`: `BFF_MCP_READ_TOKENS` from `dashboard-secrets` key
  `BFF_MCP_READ_TOKENS` with `optional: true` (precedent: `ALERT_DISCORD_WEBHOOK_URLS` in
  `51-orchestrator.yaml`), so the pod boots with the key absent. `secrets.template.yaml`: the key
  with an empty placeholder and the same comment. The secret itself is applied by hand per
  `infra/k8s/README.md`; `deploy.yml` does NOT apply this manifest: `tenant-dashboard-bff` is in its `RESTART_ONLY` list (live-only overrides; `kubectl diff` shows wide live drift), so the manifest entry documents intent only and enabling on the live cluster is a manual `kubectl set env`/patch.

### Testing Decisions

- Behaviour at the HTTP boundary only: status, body, which tenant the downstream sees, response
  shape. No test reaches into the filter's parsing or hashing helpers.
- Seam A, existing: `ServiceTokenFilter.doFilter` with `MockHttpServletRequest` /
  `MockHttpServletResponse` / `MockFilterChain`, as the 46 current tests do. `chain.getRequest()` is
  the request the handler would see, so the wrapper's headers and the attribute are asserted there.
- Seam B, new: `@WebMvcTest(McpWhoamiController.class)` with filters OFF and `.requestAttr(...)`,
  mirroring `TradesControllerWebMvcTest`.
- Seam C, new: one filters-ON `@WebMvcTest(McpWhoamiController.class)` with
  `bff.mcp-read-tokens` set by `@TestPropertySource` — the only test that proves
  filter → wrapper → `TenantContext` → handler end to end.
- Expected hashes in tests are literals computed outside the code under test (`sha256sum`), never
  with the filter's own helper.
- Every new guard is mutation-checked once by hand: break it, run `mvn test` (never
  `surefire:test`), see the named test red, restore. The mutation table in Step 1 names the guard,
  the break, and the test that must go red.

### Out of Scope

Everything under Non-goals, plus: rate limiting, token expiry, more than one tenant per token, a
tenant-scoped strategy-config read, audit persistence of MCP reads (logs only).

### Further Notes

- The ingest-token isolation tests must stay byte-for-byte as they are; if any has to change, the
  approach is wrong (failure checklist).
- `MockFilterChain.getRequest()` returns whatever object was passed to `chain.doFilter`, so
  asserting `isSameAs(req)` for shared-token tests stays valid (rule 4 passes the original request),
  while MCP tests assert on the wrapper's header values instead.

## Steps

### Step 1 — The filter recognises a tenant-bound, read-only MCP token

**Goal:** With `bff.mcp-read-tokens` configured, an MCP token can `GET` an allowlisted route as its
bound tenant and nothing else; with it empty, nothing changes for anyone.

**Blocked by:** none.

**Seams:**

- `ServiceTokenFilter.doFilter(req, res, chain)` with mock servlet objects (Seam A). Catches every
  status decision, the exact downstream headers and attribute, and the boot check. Misses the
  container's path normalisation (covered by the pre-existing firewall guard, which runs first) and
  what a handler does with the headers (Step 2, Seam C).

**Files:** `security/ServiceTokenFilter.java`, `security/ServiceTokenFilterTest.java`,
`src/main/resources/application.yml` (property + comment). Nothing else.

**Tests to add** (names indicative; one test per bullet unless noted):

1. `mcpToken_allowlistedGet_passesThrough_withBoundTenantInjected` — `GET /api/positions`, bearer
   `test-mcp-token-acme`; status 200; downstream `getHeader("X-Tenant-Id") == "acme"`,
   `getHeaders("X-Tenant-Id")` has exactly one element, `getHeaderNames()` contains it.
2. `mcpToken_headOnAllowlistedRoute_passesThrough`.
3. `mcpToken_everyAllowlistedPath_passesThrough` — loop over the 18 paths in the table.
4. `mcpToken_writeMethods_are403` — POST/PUT/DELETE/PATCH on `/api/positions/force-close`,
   `/api/positions/partial-close`, `/api/positions/arm-trail`, `/api/entries/manual`,
   `/api/account-killswitch/reset`, and POST on an allowlisted path (`/api/positions`); body
   `{"error":"forbidden"}`; chain not invoked.
5. `mcpToken_nonAllowlistedGet_is403` — `/api/admin/tenants`, `/api/admin/tenant-invites`,
   `/api/broker-credentials/status`, `/api/tenant-config`, `/api/strategy-config`,
   `/api/options-chat/messages`, `/api/positions/arm-anchors`, `/api/entries/watchlist`,
   `/api/live/connection`, `/internal/provisioning/bind`.
6. `mcpToken_trailingSlashOnAllowlistedPath_is403` — `/api/positions/` (exact match only; a
   trailing slash still passes with the shared token, as the existing test pins).
7. `mcpToken_forgedOtherTenantHeader_is403` — `X-Tenant-Id: beta` → 403, chain not invoked.
8. `mcpToken_sameTenantHeader_isAllowed` and `mcpToken_absentTenantHeader_isAllowed` — both reach
   the chain with `X-Tenant-Id == acme`.
9. `mcpToken_operatorHeader_isStripped` — send `X-Operator-Id: op@example.com`; downstream
   `getHeader("X-Operator-Id")` is null, `getHeaders` empty, `getHeaderNames` lacks it (any case).
10. `mcpToken_setsTokenIdAttribute` — downstream
    `getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE) == "87121ec9ab39"`.
11. `sharedToken_doesNotSetTokenIdAttribute_andLeavesHeadersAlone` — shared bearer with
    `X-Tenant-Id: beta` and `X-Operator-Id: op` reaches the chain as the SAME request object with
    both headers intact and no attribute.
12. `unknownToken_is401_withTheExistingBody` — `Bearer not-a-token` → 401 `{"error":"unauthorized"}`.
13. `mcpTokens_emptyMap_is401ForAnMcpLookingToken` — filter built with `""`; the acme token → 401.
14. `mcpToken_onIngestRoutes_is401` — `/internal/options-chat/ingest` (POST), `/internal/options-chat/messages`
    (GET), `/internal/darkpool/ingest`, `/internal/flow/ingest`, `/internal/flow/watchlist` (GET).
15. `ingestToken_onAllowlistedTenantRead_isStill401` and `sharedToken_onAllowlistedRoutes_stillPassesThrough`
    — the two existing credentials are unchanged.
16. `mcpToken_encodedAndTraversalPaths_are401` — `/api/%2e%2e/api/positions`,
    `/api/positions/..;a`, `/api//positions`, `/internal/options-chat/%2e%2e/%2e%2e/api/positions`
    with the MCP bearer → 401 (the guard runs before any token decision, so these are 401 not 403).
17. `secondConfiguredToken_isBoundToItsOwnTenant` — map with both acme and
    `bb705d371dc75cfce9616f51628261d27dd939fa509f59988902d3f591112556:beta`
    (`sha256("test-mcp-token-beta")`); the beta token reaches the chain as `beta`, and the beta
    token with `X-Tenant-Id: acme` → 403.
18. `malformedMcpTokenEntry_failsBoot` — each of `"nothex:acme"`, `"<64hex>"` (no tenant),
    `"<64hex>:bad tenant!"`, `"<63hex>:acme"` throws `IllegalStateException` with a prod
    `MockEnvironment`; and `malformedMcpTokenEntry_failsBoot_withoutProdProfileToo` with a plain
    `MockEnvironment` (records the unconditional decision; if the operator overrules it, this one
    test flips).
19. `wellFormedMcpTokenMap_withTrailingCommaAndSpaces_boots` — `" <hash>:acme , "` constructs.
20. `uppercaseHexInConfig_matchesTheSameToken` — the acme hash in upper case still binds.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS`; surefire summary shows
      `ServiceTokenFilterTest` with ≥ 66 tests, 0 failures, 0 errors — evidence: the `Tests run:` line
      for that class.
- [ ] The 46 pre-existing test methods are present with unchanged assertions — evidence:
      `git diff 48bf60e8 -- services/tenant-dashboard-bff/src/test/.../ServiceTokenFilterTest.java`
      shows only the constructor call-site change (`""` third arg) and additions.
- [ ] `ServiceTokenFilter` declares exactly one constructor — evidence: `grep -c 'public ServiceTokenFilter(' ...` = 1
      and `SpringComponentConstructorGuardTest` green in the same run.
- [ ] `ApplicationContextSmokeTest` and `ApplicationContextWriterEnabledSmokeTest` green in the same
      run (the property default resolves to empty) — evidence: their `Tests run:` lines.
- [ ] Mutation table, each row seen red then restored (evidence per row: the failing test name and
      its assertion message from the `mvn test` output, then the green rerun):

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | method gate | accept any method | test 4 |
      | allowlist | return `true` from the allowlist check | tests 5, 6 |
      | forged tenant | skip the `X-Tenant-Id` compare | tests 7, 17 |
      | tenant injection | wrapper returns the caller's header | test 1 (or 8) |
      | operator strip | wrapper passes `X-Operator-Id` through | test 9 |
      | ingest isolation | consult the MCP map before the ingest-prefix rule | test 14 |
      | empty map | treat `""` as one entry / accept any token when empty | test 13 |
      | firewall precedence | move the MCP branch above `hasSuspiciousPath` | test 16 |
      | boot check | drop the format validation | test 18 |
      | attribute | do not set it | test 10 |

- [ ] Log line present and token-free — evidence: run test 1 with the filter's logger at INFO and
      paste the line (`mcp read tenant=acme token_id=87121ec9ab39 method=GET path=/api/positions`);
      `grep -n 'test-mcp-token' target/surefire-reports/*ServiceTokenFilterTest*` matches nothing in
      the filter's own output lines.
- [ ] `application.yml` gains `bff.mcp-read-tokens: ${BFF_MCP_READ_TOKENS:}` with the format/mint
      comment — evidence: the diff hunk.
- [ ] `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:apply` run, then `spotless:check`
      passes — evidence: command output.

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] Any existing test in `ServiceTokenFilterTest` (or any other BFF test) must be weakened,
      renamed to change meaning, or deleted to go green.
- [ ] Making the test pass needs a second constructor on `ServiceTokenFilter`, or a change to
      `TenantContext`, `GlobalExceptionHandler`, or any controller.
- [ ] A test needs a mock of the filter's internals (hashing, parsing) rather than the servlet seam.
- [ ] The empty-map default lets any token through, or the shared token's behaviour on any route
      changes (including `/api/mcp/whoami`, where the shared token must still pass the filter).
- [ ] The ingest prefix rule has to be reordered or the MCP map consulted on an ingest path.
- [ ] The allowlist needs a prefix or pattern match (a path variable) to satisfy a route; that is a
      sign the route does not belong in this step.
- [ ] A mutation row cannot be made to go red with the planned test; the test is toothless and must
      be fixed before continuing.

### Step 2 — `GET /api/mcp/whoami`, proven end to end, and the dark wiring

**Goal:** An MCP token holder can ask the BFF which tenant it is bound to and get the token id; the
shared token cannot. The property is wired into the deployment with an empty default.

**Blocked by:** Step 1.

**Seams:**

- Seam B — `@WebMvcTest(McpWhoamiController.class)`, filters OFF, `@Import(TenantContext.class)`,
  `.requestAttr(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE, ...)`. Catches the response shape and the
  403-without-attribute rule. Misses how the attribute gets there.
- Seam C — `@WebMvcTest(McpWhoamiController.class)`, filters ON (default),
  `@Import(TenantContext.class)`, `@TestPropertySource(properties = "bff.mcp-read-tokens=87121ec9ab39dfe5f0915c4b2b25f2592e80aebba9d1ee1a081caf28491478fc:acme")`
  (`bff.service-token` resolves to the yml default `dev-shared-token`; no prod profile). Catches
  filter → wrapper → `TenantContext` → handler, the one chain nothing else proves. Misses nothing
  this step adds; it is the integration point.

**Files:** new `web/McpWhoamiController.java`, new `web/McpWhoamiControllerWebMvcTest.java` (Seam
B), new `web/McpWhoamiEndToEndWebMvcTest.java` (Seam C), `ServiceTokenFilter` only if the allowlist
entry was not already added in Step 1, `infra/k8s/58-tenant-dashboard-bff.yaml`,
`infra/secrets-template/secrets.template.yaml`.

**Tests to add:**

Seam B:
1. `whoami_withTokenAttributeAndTenant_returnsBinding` — attr `87121ec9ab39`, `X-Tenant-Id: acme` →
   200, JSON exactly `tenant_id=acme`, `token_id=87121ec9ab39`, `scope=read`, no other keys.
2. `whoami_withoutTokenAttribute_is403` — header present, no attribute → 403 `{"error":"forbidden"}`.
3. `whoami_withAttributeButNoTenantHeader_is401` — the `TenantContext` contract still applies
   (`missing_tenant`); the filter never produces this combination, the test pins the fail-closed
   order.

Seam C (all against `/api/mcp/whoami`):
4. `mcpToken_getsItsBinding` — bearer `test-mcp-token-acme`, no `X-Tenant-Id` → 200 acme /
   `87121ec9ab39` / read.
5. `mcpToken_withForgedTenant_is403` — `X-Tenant-Id: beta` → 403.
6. `mcpToken_post_is403` → 403 (filter), handler never runs.
7. `sharedToken_is403` — bearer `dev-shared-token`, `X-Tenant-Id: acme` → 403 `{"error":"forbidden"}`.
8. `unknownToken_is401` → 401 `{"error":"unauthorized"}`.
9. `mcpToken_canReadAnAllowlistedRoute_asItsTenant` — put `TradesController` in the same slice
   (`@WebMvcTest({McpWhoamiController.class, TradesController.class})`, with the two `@MockitoBean`s
   `TradesControllerWebMvcTest` uses; `PositionsController` has too many collaborators for this):
   `GET /api/trades` with the MCP bearer and NO `X-Tenant-Id` → 200 and `$.tenant_id == "acme"`; the
   reader mock is called with `"acme"`. This is the PRD's "handler sees the bound tenant" at a real
   tenant read.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS` with both new test
      classes listed and green — evidence: their `Tests run:` lines.
- [ ] Seam C test 4 response body is byte-equal to
      `{"tenant_id":"acme","token_id":"87121ec9ab39","scope":"read"}` — evidence: the test asserts
      `content().json(...)` strict and the run is green.
- [ ] Mutations seen red then restored (evidence per row as in Step 1):

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | attribute gate in controller | return 200 when attribute absent | Seam B test 2, Seam C test 7 |
      | tenant from wrapper | controller reads a hard-coded tenant | Seam C tests 4, 9 |
      | allowlist entry | remove `/api/mcp/whoami` from the allowlist | Seam C test 4 |

- [ ] `McpWhoamiController` has exactly one constructor — evidence: `SpringComponentConstructorGuardTest`
      green in the run.
- [ ] `58-tenant-dashboard-bff.yaml` gains `BFF_MCP_READ_TOKENS` via `secretKeyRef` to
      `dashboard-secrets` with `optional: true` and a comment naming the filter and the fail-closed
      default; `secrets.template.yaml` gains the key with an empty placeholder and the mint recipe —
      evidence: both diff hunks; `kubectl apply --dry-run=client -f infra/k8s/58-tenant-dashboard-bff.yaml`
      parses (if `kubectl` is on this machine; otherwise `python3 -c 'import yaml,sys; list(yaml.safe_load_all(open(sys.argv[1])))' infra/k8s/58-tenant-dashboard-bff.yaml`).
- [ ] No other manifest, workflow or dashboard file changed — evidence: `git diff --stat 48bf60e8`
      lists only the files named in Steps 1 and 2 plus this plan.
- [ ] `spotless:apply` then `spotless:check` on the module pass — evidence: command output.

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] Seam C cannot be made to work with the real `ServiceTokenFilter` bean in the slice (e.g. the
      slice does not pick it up, or needs the prod profile); do not fall back to testing only with
      filters off.
- [ ] The shared token has to be special-cased in the filter for `/api/mcp/whoami` to return 403.
- [ ] `TenantContext` or `GlobalExceptionHandler` needs a change.
- [ ] The manifest change requires the secret key to exist (no `optional: true`), or any env value
      other than the empty default is committed.
- [ ] An existing WebMvcTest has to change because the new controller or filter bean is now
      constructed in its slice.

## Repo gates

- `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:apply` before every commit; CI runs
  `spotless:check` per module and the root pom check.
- Full `mvn test` for the module for every mutation check; never `surefire:test` (plugin-goal runs
  skip compilation and a mutant "survives" falsely).
- `SpringComponentConstructorGuardTest`: any `@Component` with more than one constructor needs
  exactly one `@Autowired`. Plan keeps one constructor on both touched/added components.
- `ApplicationContextSmokeTest` boots the full context with repo defaults; the new property must
  default to empty.
- `infra/k8s/58-tenant-dashboard-bff.yaml`: `deploy.yml` does NOT apply this manifest: `tenant-dashboard-bff` is in its `RESTART_ONLY` list (live-only overrides; `kubectl diff` shows wide live drift), so the manifest entry documents intent only and enabling on the live cluster is a manual `kubectl set env`/patch (orchestrator-verified 2026-11-13). The secret
  (`dashboard-secrets`) is applied manually from `infra/secrets-template/` per `infra/k8s/README.md`,
  never from the repo glob. With `optional: true` the rollout is safe before the key exists.
- Not applicable here: Temporal version gates, schema regeneration, the sidecar image RTH rule, PG
  grants.
- Commit messages end with the attribution lines the session supplies; no push from the builder.

## Open questions

1. **Boot check scope.** PRD says malformed entries fail boot under the `prod` profile. Plan:
   unconditional (fail in every profile), one test pins it. If the operator wants prod-only, flip
   test 18's non-prod variant and gate the throw on `environment.acceptsProfiles("prod")`.
2. **`/api/entries/*` reads inside the allowlist.** `quote`, `chain`, `chain/expirations` are gated
   by `manual-entry.write-enabled` and the manual-entry tenant allowlist and 404 otherwise. Plan:
   allowlist them anyway (they are the only quote/chain reads the BFF has); the MCP server in step 2
   must tolerate a 404 there. Alternative: a dedicated read, out of scope here.
3. **`notional_cap_pct_of_capital_base` for `get_portfolio_summary`.** The only source is
   `/api/strategy-config`, which the operator excluded. Plan: excluded; step 2 either drops the
   headroom field or asks for a narrow tenant-scoped read added to the allowlist.
4. **`/api/positions/arm-anchors`, `/api/entries/watchlist`, `/api/live/connection`,
   `/api/live-promotion`, `/api/proximity`.** Not named by the operator; plan excludes them. Adding
   any later is one allowlist line plus one test.
5. **Token-id length.** Plan: 12 hex chars (48 bits), enough to tell tokens apart in logs and
   useless for recovering anything. Change the constant if a different width is preferred.
6. **How the live BFF receives `OPTIONS_CHAT_INGEST_TOKEN`.** It is not in the repo manifest and
   `git log -S` finds no history; it is presumably set out of band. The plan still adds
   `BFF_MCP_READ_TOKENS` to the manifest (optional secret key) as documentation; CORRECTION (orchestrator, 2026-11-13): `deploy.yml` does NOT apply this manifest: `tenant-dashboard-bff` is in its `RESTART_ONLY` list (live-only overrides; `kubectl diff` shows wide live drift), so the manifest entry documents intent only and enabling on the live cluster is a manual `kubectl set env`/patch. The step-3 runbook should confirm the mechanism.
7. **Port-forward vs NetworkPolicy** (step 2 concern, noted so it is not lost): the BFF
   NetworkPolicy admits only dashboard, api-gateway and Prometheus pods; `kubectl port-forward`
   traffic arrives from the node, which kube-router permits for probes, so it is expected to work
   but has not been verified here.
