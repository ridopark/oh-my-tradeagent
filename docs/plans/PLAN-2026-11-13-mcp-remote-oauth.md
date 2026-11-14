# PLAN — 2026-11-13 mcp-remote-oauth

PRD: issue #1060 (remote MCP server with OAuth: Google sign-in → `dashboard_user` membership). Branch
`feat/mcp-remote-oauth`, base `d3f02dac`. Builds on #1053 (merged: local stdio server in
`services/mcp-server`, BFF MCP read-token class, `GET /api/mcp/whoami`, `GET /api/notional-cap`;
plans `PLAN-2026-11-13-bff-mcp-read-token.md`, `PLAN-2026-11-13-mcp-server.md`).

Six independently shippable PRs, eight build steps (PR 3 is three steps). Every PR merges dark:
nothing listens publicly, no BFF credential class is live, no Deployment is applied until the
operator runs the runbook. Enabling the public endpoint is NOT part of this plan.

Modules touched: `services/tenant-dashboard-bff` (Java), `services/mcp-server` (Python/uv),
`dashboard` (Next.js), `infra/k8s`, `infra/secrets-template`, `.github/workflows/{deploy,build-images}.yml`,
`docs/ops`.

## Goal

A tenant member adds `https://mcp.<domain>/mcp` to Claude / Gemini / ChatGPT / Claude Code / Gemini
CLI, signs in with the Google account they already use for the dashboard, consents once, and the
same seven read-only tools + `analyze_positions` prompt answer for exactly the tenants that
`dashboard_user` says they belong to. The BFF re-checks that membership on every request. Nothing in
this work can trade, and nothing in this work opens the endpoint: that is one operator runbook.

**Done when** (all commands from the repo root unless stated):

- PR 1 — `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS`; `ServiceTokenFilterTest`
  keeps all 70 pre-existing tests (constructor call sites gain one `null` argument, nothing else)
  plus the Step 1 tests; `McpDelegatedEndToEndWebMvcTest` green; `ApplicationContextSmokeTest`,
  `SpringComponentConstructorGuardTest` green. With `bff.mcp-delegated.enabled=false` (the default)
  an EdDSA assertion bearer is a 401 identical to an unknown token. With it enabled and the test
  public key configured, a valid assertion for `(google, sub-1, acme)` whose membership exists →
  `GET /api/mcp/whoami` 200 `{"tenant_id":"acme","token_id":"dlg-<12hex>","scope":"read"}`;
  membership absent → 403; expired / wrong `aud` / wrong key / `alg` ≠ `EdDSA` → 401; forged
  `X-Tenant-Id` → 403; `POST` → 403; non-allowlisted path → 403; `X-Operator-Id` stripped.
- PR 2 — `RUN_DB_ITS=true mvn -B -ntp -pl services/tenant-dashboard-bff verify` → `BUILD SUCCESS`
  with `McpOauthMigrationIT` green: role `mcp_gateway` can read `(provider, subject, tenant_id)` of
  `dashboard_user` and nothing else of it, has CRUD on the six `mcp_oauth_*` tables, and cannot
  touch `dashboard_user_invite`; `dashboard_readonly` can SELECT `mcp_oauth_grant` and
  `mcp_oauth_client` and cannot UPDATE; `dashboard_writer` can `UPDATE mcp_oauth_grant SET
  revoked_at … WHERE id = ? AND provider = ? AND subject = ?` and cannot INSERT. Each `GRANT`
  removed → that IT red (recorded).
- PR 3 — `cd services/mcp-server && uv sync --locked && uv run ruff check . && uv run pytest -q` →
  all green, including: the OAuth conformance suite over `httpx.ASGITransport` (metadata, 401 +
  `WWW-Authenticate`, DCR policy, CIMD, PKCE, code reuse, refresh rotation + reuse → grant revoked,
  revoke, consent CSRF, `/token` form / `/register` JSON), tenant mapping (0 → denied at sign-in,
  1 → implicit, 2 → `tenant` required-in-grant, membership removed → next call refused), the
  redaction sweep over all seven tools through HTTP, rate-limit and kill-switch tests, and one real
  `mcp.Client` over `streamable_http_client` against uvicorn on `127.0.0.1` listing 7 tools + 1
  prompt with a bearer obtained through the flow. All pre-existing stdio tests still pass;
  `uv run omt-mcp` behaviour (exit codes, messages) is unchanged. `docker build -f
  services/mcp-server/Dockerfile .` succeeds and the image's `python -m ohmytradeagent_mcp.gateway`
  exits 2 with a one-line stderr message when `OMT_PUBLIC_URL` is unset.
- PR 4 — BFF: `McpGrantsControllerWebMvcTest` / `McpGrantsRevokeControllerWebMvcTest` green; with
  the flags off both routes 404; MCP token on `/api/mcp/grants` → 403 (filter pin). Dashboard:
  `cd dashboard && npm run lint && npm run typecheck && npm run test:lib` green; with
  `MCP_CONNECTED_APPS_ENABLED` unset the `/connected-apps` route and its Nav link do not render.
- PR 5 — `python3 -c 'import yaml,sys; list(yaml.safe_load_all(open(sys.argv[1])))'
  infra/k8s/68-mcp-server.yaml` parses; `OMT_GATEWAY_DISABLED` is `"true"` in the manifest;
  `deploy.yml` lists `mcp-server` in `SERVICES`, the dispatch options, the diff `case`, and the
  bootstrap guard; `build-images.yml` has a `mcp-server` matrix row; `tenant-dashboard-bff` is still
  in `RESTART_ONLY`; `docs/ops/mcp-remote-gateway-runbook.md` exists with every operator step
  (secrets, Google redirect URI, Cloudflare ingress/WAF/rate-limit via API, first apply, enable,
  verify, kill switch, rollback). The PR is flagged `NOT_REVIEWED` by the bot (workflow edit) and is
  reviewed by hand.
- PR 6 — `docs/ops/mcp-client-compat.md` has one section per client (claude.ai / Desktop / mobile
  connector, Claude Code `--transport http`, Gemini CLI, Gemini app, ChatGPT) with the exact setup
  steps, the callback/redirect the client uses, what to expect at each OAuth hop, a results table
  the operator fills after enabling, and a data-retention paragraph per provider that links the
  provider's policy page. `services/mcp-server/README.md` gains a "Remote (OAuth) mode" section.

## Non-goals

- Enabling anything on the live cluster: no `kubectl apply/set env/patch`, no Cloudflare API call,
  no Google console change by the builder. Those are runbook steps for the operator.
- Any write or trading capability; any route added to `MCP_ALLOWLIST`; any change to the seven tools'
  data contracts beyond the optional `tenant` argument.
- Facebook sign-in, Gemini Enterprise/org registration, public directory listings, changes to the
  dashboard's Cloudflare Access policy, removing stdio mode (all PRD out-of-scope).
- `audit_log` rows for tool calls (structured stderr line only); multi-replica gateway; token
  introspection endpoint; operator-sees-all of any kind.
- Changing `TenantContext`, `GlobalExceptionHandler`, `PositionsReader`, any existing handler, or
  the behaviour of the shared / ingest / static-MCP credentials.

## Spec

### Problem Statement

The read-only MCP server works for one operator on the LAN with a pasted token and a port-forward.
Tenant members off the LAN, and hosted AI apps that connect from their own clouds, cannot use it,
and every token mint or revocation needs an operator and a BFF restart.

### Solution

A hosted gateway (the same `services/mcp-server` package in a Streamable HTTP mode) with a built-in
OAuth 2.1 authorization server that signs the user in with Google, maps `(google, sub)` to the
tenant set in `dashboard_user`, shows a consent screen, and issues short-lived opaque access tokens
with rotating refresh tokens. Each tool call turns into BFF reads carried by a 60-second delegated
assertion (`EdDSA`, signed by the gateway) naming the subject and tenant; the BFF verifies it,
re-checks `dashboard_user`, and applies exactly the existing MCP read rules. Members list and revoke
their connected apps in the dashboard. Everything ships dark.

### User Stories

PRD #1060 stories 1–40. Mapping to steps: 1–7, 32 → Steps 3a/3b/5/6; 8, 9, 31 → 3b; 10–13, 37, 38 →
3a; 14, 15, 16, 26 → 1 and 3a; 17, 18 → 4 (and 3b for revocation semantics); 19, 20 → 3b; 21 → 3a;
22 → 3c; 23, 24, 35, 36 → 5 and 3c; 25 → 1 (the gateway holds a signing key, not a tenant
credential); 27, 28 → unchanged sanitiser, re-proven in 3a; 29, 30 → 3b/3c; 33 → 3b; 34 → every
step; 39 → the seams below; 40 → 6.

### Implementation Decisions

**What the installed SDK (`mcp==2.3.0`, inspected in the `services/mcp-server` venv, read not run)
provides, and what this plan builds.**

Provided and used as-is:

- `MCPServer(..., auth_server_provider=…, auth=AuthSettings(...))` and `MCPServer.streamable_http_app(json_response=…, stateless_http=…, transport_security=…)`;
  `MCPServer.custom_route(path, methods)` for our own routes.
- `mcp.server.auth.provider.OAuthAuthorizationServerProvider` protocol (`get_client`,
  `register_client`, `authorize` → redirect URL string, `load_authorization_code`,
  `exchange_authorization_code`, `load_refresh_token`, `exchange_refresh_token`,
  `load_access_token`, `revoke_token`), the `AuthorizationCode` / `RefreshToken` / `AccessToken`
  models (with `subject`, `resource`, `claims`), `construct_redirect_uri`, `ProviderTokenVerifier`.
- `create_auth_routes`: `/.well-known/oauth-authorization-server` (GET, CORS), `/authorize` (GET +
  POST, no CORS), `/token` (POST, **form-encoded**, body-limited, CORS), `/register` (POST,
  **JSON**), `/revoke` (POST form); `create_protected_resource_routes` at
  `/.well-known/oauth-protected-resource/mcp`; `build_metadata` already advertises
  `code_challenge_methods_supported: ["S256"]`, `registration_endpoint` and `revocation_endpoint`
  when enabled, `token_endpoint_auth_methods_supported: ["client_secret_post","client_secret_basic"]`.
- Handler-side enforcement: PKCE is S256-only (`code_challenge_method: Literal["S256"]`), the
  verifier is checked at `/token`, `redirect_uri` is exact-matched against the registered list
  (`OAuthClientInformationFull.validate_redirect_uri`) at `/authorize` and re-checked at `/token`,
  refresh tokens are checked for client match and expiry, DCR mints `client_id` (uuid4) and a secret
  for non-`none` auth methods, `grant_types` must include `authorization_code`.
- `RequireAuthMiddleware` (401 with `WWW-Authenticate: Bearer ... resource_metadata="…"`),
  `BearerAuthBackend`, `AuthContextMiddleware` + `get_access_token()` (a contextvar readable inside
  tool functions).
- `TransportSecuritySettings(allowed_hosts=[…])` (DNS-rebinding protection; `None` disables it).
- Transitive runtime deps already present: `starlette`, `uvicorn`, `python-multipart`,
  `cryptography`, `PyJWT`. The plan declares the ones we import directly.

Not provided — built here:

- Redirect-URI **policy** at registration (`/register` accepts any URI; our `register_client`
  raises `RegistrationError("invalid_redirect_uri")` unless each URI is `https://` with a host that
  is not an IP literal, or `http://127.0.0.1[:port]/…` / `http://localhost[:port]/…` / `http://[::1]…`;
  no wildcards, no fragments).
- **CIMD** server side: the SDK has only the metadata field `client_id_metadata_document_supported`
  and never sets it. Our `get_client` treats a `client_id` that is an `https://` URL with a non-root
  path as a Client ID Metadata Document: fetch it (10 s timeout, 64 KiB cap, `application/json`),
  require the document's `client_id` to equal the URL, apply the same redirect-URI policy, cache it
  in memory for 1 h, and set `token_endpoint_auth_method: "none"`. The AS metadata document is
  served by our own handler built from the SDK's `build_metadata(...)` plus
  `client_id_metadata_document_supported: true` (the SDK route for that path is replaced in the
  Starlette app after construction; one test pins the field).
- Google federation (`authorize` → Google → `/oauth/google/callback` → consent → code), consent page
  (CSRF, CSP), the `(google, sub)` → tenants mapping, tenant selection, persistence, refresh-token
  reuse detection, unused-client expiry, opaque-token verification with membership re-check, the
  delegated assertion, rate limits, kill switch, health/readiness, the audit line, the `tenant`
  tool argument, and the BFF credential class.

**A. BFF delegated-assertion credential class (PR 1).**

- Properties: `bff.mcp-delegated.enabled` (env `BFF_MCP_DELEGATED_ENABLED`, default `false`) and
  `bff.mcp-delegated.public-key` (env `BFF_MCP_DELEGATED_PUBLIC_KEY`, default empty; base64 of the
  SPKI DER of an Ed25519 public key, i.e. `openssl pkey -in gateway.pem -pubout -outform DER | base64 -w0`).
  Enabled with an empty or unparseable key → `IllegalStateException` at boot (same stance as the
  malformed MCP token map). Disabled → no bean, class inert, an assertion bearer is rule-6 401.
- New read-only datasource to the **dashboard** DB, connecting as the existing `dashboard_readonly`
  role (V2 grants it `SELECT ON dashboard_user`; the BFF pod already carries
  `DASHBOARD_READONLY_PASSWORD` for the Flyway placeholder): `bff.datasource.dashboard-readonly`
  (`jdbc-url ${DASHBOARD_READONLY_URL:jdbc:postgresql://localhost:5432/dashboard}`, username
  `dashboard_readonly`, password `${DASHBOARD_READONLY_PASSWORD:}`), beans
  `dashboardReadonlyDataSource` / `dashboardReadonlyDsl` in `DataSourceConfig`, both
  `@ConditionalOnProperty(name = "bff.mcp-delegated.enabled", havingValue = "true")`.
- New `security/DashboardMembershipReader` (`@Component`, same condition, one constructor taking
  `@Qualifier("dashboardReadonlyDsl") DSLContext`): `boolean isMember(provider, subject, tenant)`
  = `SELECT 1 FROM dashboard_user WHERE provider = ? AND subject = ? AND tenant_id = ? LIMIT 1`
  (plain-SQL jOOQ). Any exception → `false` plus one WARN line naming the exception class, never the
  subject (fail-closed).
- New `security/DelegatedAssertionAuthenticator` (`@Component`, same condition, one constructor:
  `@Value("${bff.mcp-delegated.public-key:}") String publicKeyBase64`, `DashboardMembershipReader`).
  `authenticate(String presented)` returns a small record `Result(Status status, String tenant,
  String credentialId, String clientId)` with `Status ∈ {NOT_AN_ASSERTION, INVALID, NOT_MEMBER, OK}`:
  - `NOT_AN_ASSERTION` unless the string has exactly two dots and both the header and payload
    base64url-decode to JSON objects.
  - Header: `alg` must be exactly `"EdDSA"` (anything else, including `none`, `HS256`, `RS256` →
    `INVALID`). Signature verified with `Signature.getInstance("Ed25519")` (JDK 21, no new
    dependency) over the ASCII bytes of `header.payload`, with the key parsed once at construction
    via `KeyFactory.getInstance("Ed25519")` + `X509EncodedKeySpec`.
  - Claims (all required, else `INVALID`): `iss == "omt-mcp-gateway"`, `aud == "tenant-dashboard-bff"`,
    `provider == "google"`, `sub` non-blank ≤ 255 chars, `tenant` matching `[A-Za-z0-9_-]+`,
    `client_id` string ≤ 512 chars, `iat` and `exp` integers with `exp > now`, `iat ≤ now + 30`,
    `exp − iat ≤ 300`.
  - Then `isMember(provider, sub, tenant)`; false → `NOT_MEMBER`. `credentialId =
    "dlg-" + first 12 hex of sha256(provider + ":" + sub)`.
- `ServiceTokenFilter` keeps ONE constructor; it gains a fifth parameter `@Nullable
  DelegatedAssertionAuthenticator delegated` after `Environment` (null when the bean is absent).
  The 14 call sites in `ServiceTokenFilterTest` pass `null`. The body of today's rule 5 after a
  static token matched (method gate, allowlist, forged-tenant check, log, wrapper, attribute,
  `chain.doFilter`) is extracted into one private method `applyMcpReadRules(request, response,
  chain, boundTenant, credentialId, clientId)`; rule 5 calls it with `clientId = null` and its
  observable behaviour is byte-for-byte unchanged (the 70 tests pin it). New **rule 5b**, after
  rule 5 and before rule 6: if `delegated != null`, `authenticate(presented)`; `NOT_AN_ASSERTION`
  → fall through to rule 6; `INVALID` → 401 (same body as unknown token); `NOT_MEMBER` → 403
  `{"error":"forbidden"}`; `OK` → `applyMcpReadRules(..., tenant, credentialId, clientId)`.
  Log line for delegated requests: `mcp read tenant={} token_id={} client_id={} method={} path={}`
  (the static-token line is unchanged). Never the subject, never the assertion.
- `/api/mcp/whoami` works unchanged for the delegated class (it reads the attribute), returning
  `token_id = "dlg-…"`. No controller change.
- Wiring, dark: `application.yml` properties with comments; `58-tenant-dashboard-bff.yaml` gains
  `BFF_MCP_DELEGATED_PUBLIC_KEY` from `dashboard-secrets` (`optional: true`) and
  `DASHBOARD_READONLY_URL` as a literal; `secrets.template.yaml` gains the key with the derive
  recipe. `BFF_MCP_DELEGATED_ENABLED` is NOT set in the manifest (default false). The manifest is
  `RESTART_ONLY` in `deploy.yml` (documentation of intent; enabling is `kubectl set env` by hand).

**B. Persistence and the gateway DB role (PR 2).** BFF-owned Flyway, `db/dashboard/V17__mcp_oauth.sql`
(tables + grants to existing roles) and `V18__mcp_gateway_role.sql` (role + grants), mirroring
V4/V5. All token-like values are stored as `sha256` hex, never raw.

- `mcp_oauth_client(client_id TEXT PK, metadata JSONB NOT NULL, client_secret_hash TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_used_at TIMESTAMPTZ, expires_at TIMESTAMPTZ NOT NULL)`
  — DCR clients; `expires_at = created_at + 30 d`, pushed forward on every use; a registration
  sweeps expired never-used rows. CIMD clients are not stored (in-memory cache).
- `mcp_oauth_pending_auth(state_hash TEXT PK, client_id TEXT NOT NULL, params JSONB NOT NULL,
  csrf_hash TEXT, provider TEXT, subject TEXT, tenants TEXT[], created_at, expires_at)` — one
  `/authorize` in flight (10 min); `csrf_hash/provider/subject/tenants` filled after the Google
  callback, consumed by the consent POST.
- `mcp_oauth_grant(id uuid PK DEFAULT gen_random_uuid(), client_id TEXT NOT NULL, provider TEXT NOT NULL,
  subject TEXT NOT NULL, tenants TEXT[] NOT NULL, created_at, last_used_at, revoked_at)` + index on
  `(provider, subject)`.
- `mcp_oauth_code(code_hash TEXT PK, grant_id uuid NOT NULL REFERENCES mcp_oauth_grant, client_id,
  code_challenge TEXT NOT NULL, redirect_uri TEXT NOT NULL, redirect_uri_provided_explicitly BOOLEAN NOT NULL,
  resource TEXT, scopes TEXT[], expires_at NOT NULL, consumed_at)`.
- `mcp_oauth_refresh_token(token_hash TEXT PK, grant_id uuid NOT NULL REFERENCES mcp_oauth_grant,
  expires_at NOT NULL, rotated_at TIMESTAMPTZ, replaced_by_hash TEXT)`.
- `mcp_oauth_access_token(token_hash TEXT PK, grant_id uuid NOT NULL REFERENCES mcp_oauth_grant,
  expires_at NOT NULL)`.
- Grants in V17: `GRANT SELECT ON mcp_oauth_grant, mcp_oauth_client TO dashboard_readonly` (listing
  in PR 4); `GRANT SELECT, UPDATE ON mcp_oauth_grant TO dashboard_writer` (revoke in PR 4; table-level
  SELECT covers the `WHERE` columns, the PG16 rule).
- V18: role `mcp_gateway` created `IF NOT EXISTS` with `PASSWORD '${mcp_gateway_password}'`
  (placeholder `spring.flyway.placeholders.mcp_gateway_password: ${MCP_GATEWAY_DB_PASSWORD}`, no
  default, exactly as V2/V5), `GRANT CONNECT`/`USAGE`, `GRANT SELECT (provider, subject, tenant_id)
  ON dashboard_user TO mcp_gateway` (column-level: the `WHERE provider = ? AND subject = ?` columns
  are in the grant), `GRANT SELECT, INSERT, UPDATE, DELETE ON` the six tables. Nothing on
  `dashboard_user_invite`, `email`, or any other table.
- **Deploy hazard (explicit):** the placeholder has no default, so the BFF refuses to boot when
  `MCP_GATEWAY_DB_PASSWORD` is absent. Before PR 2 merges the operator must add the key to
  `dashboard-secrets` and `kubectl -n copytrade set env deployment/tenant-dashboard-bff
  --from=secret/dashboard-secrets --keys=MCP_GATEWAY_DB_PASSWORD` (the manifest is `RESTART_ONLY`,
  so the repo change alone does nothing live). The step checklist records a read-only
  `kubectl get deploy … env names` check by the orchestrator/operator, not the builder.

**C. Gateway (PR 3, Steps 3a–3c).** New subpackage `ohmytradeagent_mcp/gateway/` beside the stdio
code; console script `omt-mcp-gateway`; `python -m ohmytradeagent_mcp.gateway`. New direct
dependencies: `uvicorn`, `pyjwt[crypto]` (Ed25519 signing + Google ID-token verification),
`asyncpg` (Postgres store). `mcp` stays pinned `==2.3.0`.

- Settings (env, all read once at start; missing required → exit 2 with one stderr line):
  `OMT_PUBLIC_URL` (e.g. `https://mcp.<domain>`; issuer == resource == this), `OMT_BFF_URL`,
  `OMT_GATEWAY_DB_URL` (`postgresql://mcp_gateway@postgres:5432/dashboard`),
  `MCP_GATEWAY_DB_PASSWORD`, `OMT_GOOGLE_CLIENT_ID`, `OMT_GOOGLE_CLIENT_SECRET`,
  `MCP_GATEWAY_SIGNING_KEY` (Ed25519 private key PEM), `OMT_GATEWAY_DISABLED` (default `false`),
  `OMT_LISTEN` (`0.0.0.0:8000`), `OMT_ALLOWED_HOSTS` (default: host of `OMT_PUBLIC_URL`),
  `OMT_RATE_TOOLS_PER_MIN_SUBJECT` (60), `OMT_RATE_TOOLS_PER_MIN_CLIENT` (300),
  `OMT_RATE_AUTH_PER_MIN_IP` (30).
- Composition root `build_gateway_app(settings, store, idp, signer, bff_factory, clock) → Starlette`
  so tests inject `InMemoryStore`, `FakeIdp`, a test key and the stub BFF. MCP endpoint at `/mcp`
  with `stateless_http=True, json_response=True` (one replica, no session affinity, every response
  a plain JSON body, which is valid Streamable HTTP for every target client).
  `TransportSecuritySettings(allowed_hosts=[<public host>])`.
- `AuthSettings(issuer_url=OMT_PUBLIC_URL, resource_server_url=OMT_PUBLIC_URL + "/mcp",
  client_registration_options=ClientRegistrationOptions(enabled=True, valid_scopes=["read"],
  default_scopes=["read"]), revocation_options=RevocationOptions(enabled=True),
  validate_token_resource=False)` — our verifier checks `resource` itself when present (RFC 8707
  indicator must equal the resource URL; absent is accepted because several target clients do not
  send it). A second protected-resource document is also served at the root
  `/.well-known/oauth-protected-resource` (same body) for clients that probe the root.
- `OAuthIdentityResolver` (new `TenantResolver`): reads `get_access_token()`; the token's grant
  carries `tenants`; with one tenant it is implicit; with several, the tool's `tenant` argument is
  required and must be in the set, else `ToolError("tenant required: this grant covers more than one
  tenant; pass tenant=<id>")` / `ToolError("not authorised for tenant <id>")` — never listing the
  others. Returns `TenantBinding(tenant_id, token_id="dlg-" + 12 hex of sha256("google:" + sub))`.
- Tool layer change (shared by both modes): `build_server(scope, *, today=None)` where `scope` is
  `Callable[[str | None], Awaitable[Scope]]` and `Scope = (binding, bff)`. Stdio passes a constant
  scope that raises `ToolError` if a `tenant` argument names a different tenant; HTTP passes the
  resolver, which also constructs a per-request `BffClient`. Every tool gains `tenant: str | None =
  None` as its last parameter; nothing else in any tool changes.
- `BffClient` gains a token **provider** (`str | Callable[[], str]`); the HTTP mode mints a fresh
  assertion per BFF call. `Signer.assertion(sub, tenant, client_id)` → compact JWS, header
  `{"alg":"EdDSA","typ":"JWT"}`, claims `iss="omt-mcp-gateway"`, `aud="tenant-dashboard-bff"`,
  `provider="google"`, `sub`, `tenant`, `client_id`, `iat=now`, `exp=now+60`. The gateway still
  sends `X-Tenant-Id: <tenant>` (the BFF 403s on mismatch, as today).
- Token verification (`load_access_token`, used by the SDK's `ProviderTokenVerifier`): hash the
  bearer, look it up, require `expires_at > now`, grant `revoked_at IS NULL`, and **re-check
  membership for every tenant in the grant** against `dashboard_user` (drop tenants no longer held;
  if none remain → `None` → 401). Returns `AccessToken(token, client_id, scopes=["read"],
  expires_at, resource, subject=sub, claims={"tenants": [...], "grant_id": …})`. `last_used_at` on
  the grant is bumped at most once a minute.
- AS provider (`OmtAuthorizationServerProvider`): `authorize` stores a pending auth keyed by a
  random 256-bit `state` (hash stored) and returns Google's authorization URL (`openid email`,
  `prompt=select_account`, our `redirect_uri = OMT_PUBLIC_URL + "/oauth/google/callback"`,
  `nonce`). `GET /oauth/google/callback` (custom route) exchanges the code at Google, verifies the
  ID token with Google's JWKS (`iss`, `aud` = our client id, `nonce`, `exp`, `email_verified ==
  true`), looks up tenants for `(google, sub)`; empty → 302 to the client's `redirect_uri` with
  `error=access_denied&error_description=not_a_member&state=…`; else render the consent page.
  `POST /oauth/consent` (custom route): CSRF token (random, hash stored on the pending row, single
  use) + `Sec-Fetch-Site`/`Origin` same-origin check; `approve` with ≥ 1 selected tenant ⊆ the set →
  create `mcp_oauth_grant`, mint a 256-bit code (5 min, hash stored), 302 to `redirect_uri` with
  `code` + `state`; `deny` → `error=access_denied`. Consent page: plain HTML, no scripts, escaped
  client name (CIMD/DCR `client_name`, ≤ 80 chars) and client id, the tenant list as checkboxes, the
  sentence "Read-only. This app cannot place, change or cancel orders.", headers
  `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self';
  frame-ancestors 'none'`, `X-Frame-Options: DENY`, `Cache-Control: no-store`, `Referrer-Policy:
  no-referrer`.
- Codes are single-use (`consumed_at`; a second exchange → `invalid_grant` and the grant's
  outstanding refresh tokens are revoked, RFC 6749 §4.1.2). `exchange_authorization_code` mints an
  access token (1 h) + refresh token (30 d), both 256-bit random, hashes stored.
  `exchange_refresh_token` rotates: the presented token gets `rotated_at` + `replaced_by_hash`, a new
  pair is issued; presenting a token whose `rotated_at` is set (**reuse**) revokes the whole grant
  (`revoked_at = now`) and returns `invalid_grant`. `revoke_token` revokes the grant behind either
  token type. `register_client` applies the redirect-URI policy and sweeps expired unused clients.
- Rate limits (3c): token buckets in process. Per subject (`OMT_RATE_TOOLS_PER_MIN_SUBJECT`) and
  per client id (`OMT_RATE_TOOLS_PER_MIN_CLIENT`) on tool calls → `ToolError("rate_limited: retry
  after <n> s")` and audit outcome `rate_limited` (the MCP layer has no HTTP status; see Open
  questions). Per client IP (`CF-Connecting-IP` header if present, else the peer) on
  `/authorize`, `/token`, `/register`, `/revoke`, `/oauth/*` → HTTP 429 with `Retry-After`.
- Kill switch: `OMT_GATEWAY_DISABLED=true` → outermost ASGI middleware returns 503
  `{"error":"disabled"}` for every path except `/healthz`. `/healthz` is 200 when the process is up;
  `/readyz` is 200 only when not disabled and the store answers `SELECT 1`.
- Audit: one stderr line per tool call: `mcp tool subject_hash=<12hex> client_id=<…> tenant=<…>
  tool=<…> outcome=ok|error|denied|rate_limited`. No tokens, no emails, no subject.
- Store: `OAuthStore` Protocol with `InMemoryStore` (tests; also the single-process reference) and
  `PostgresStore` (asyncpg, SQL matching V17 exactly). Postgres tests run only when
  `OMT_TEST_DATABASE_URL` is set (skipped otherwise); CI gains a `postgres:16` service for the job in
  PR 5's workflow edit. The grants themselves are proven in PR 2's Testcontainers IT.
- Dockerfile (3c) mirrors `services/stc-intent-service/Dockerfile`: `python:3.12-slim`, pinned `uv`
  binary, `uv sync --frozen --no-dev`, non-root user, `EXPOSE 8000`, `ENTRYPOINT ["python", "-m",
  "ohmytradeagent_mcp.gateway"]`. No model warm-up layer.

**D. "Connected AI apps" (PR 4).**

- BFF (both behind `bff.mcp-delegated.enabled`; the revoke route additionally behind
  `dashboard.writer.enabled`, like `ProvisioningBindController`): `GET /api/mcp/grants` and `POST
  /api/mcp/grants/{id}/revoke`, shared-token only (NOT in `MCP_ALLOWLIST`; pinned 403 for an MCP
  token). The dashboard server injects the verified identity as `X-Identity-Provider` and
  `X-Identity-Subject` (same trust model as `X-Tenant-Id` behind the shared token; a new
  `IdentityHeaders` helper beside `TenantContext`, not a change to it; subject charset
  `[A-Za-z0-9._-]{1,255}`, provider `[a-z]{1,32}`; missing → 401 `missing_identity`). List reads
  `mcp_oauth_grant ⋈ mcp_oauth_client` via `dashboardReadonlyDsl` for that identity only, returning
  `id, client_name, client_id, tenants, created_at, last_used_at, revoked_at`. Revoke runs `UPDATE
  mcp_oauth_grant SET revoked_at = now() WHERE id = ? AND provider = ? AND subject = ? AND revoked_at
  IS NULL` via `dashboardWriterDsl`; 0 rows → 404 (never reveals another identity's grant). Access
  and refresh tokens die with the grant because every token lookup joins `revoked_at IS NULL`.
- Dashboard: `auth.ts` jwt callback stamps `token.provider = account.provider` and `token.subject =
  account.providerAccountId` on initial sign-in; `session.identity = {provider, subject}`; types
  augmented. New page `app/connected-apps/page.tsx` (server component, `force-dynamic`), dark-gated
  by `MCP_CONNECTED_APPS_ENABLED === "true"` (Nav link and route both hidden otherwise, the
  `BROKER_CREDENTIALS_WRITE_ENABLED` pattern), listing grants with a per-row Revoke `SubmitButton`
  calling a server action that POSTs the revoke through `lib/bff.ts` (new `getConnectedApps()` /
  `revokeConnectedApp(id)` using the existing POST helper with the identity headers). A session
  without `identity` (minted before this deploy) renders "Sign out and back in to manage connected
  apps." A pure helper `lib/connectedApps.ts` (format rows, label never-used, sort revoked last)
  gets a `lib/connectedApps.test.mjs`.

**E. Manifests, workflows, edge (PR 5).**

- `infra/k8s/68-mcp-server.yaml`: Deployment `mcp-server` (replicas 1, `Recreate`, image
  `ghcr.io/ridopark/oh-my-tradeagent-mcp-server:latest`, port 8000, `readinessProbe /readyz`,
  `livenessProbe /healthz`, resources 50m/128Mi–512Mi, non-root), env: `OMT_PUBLIC_URL` literal,
  `OMT_BFF_URL=http://tenant-dashboard-bff:8083`, `OMT_GATEWAY_DB_URL` literal,
  `MCP_GATEWAY_DB_PASSWORD` / `MCP_GATEWAY_SIGNING_KEY` from `dashboard-secrets`,
  `OMT_GOOGLE_CLIENT_ID` / `OMT_GOOGLE_CLIENT_SECRET` from `dashboard-secrets` keys `AUTH_GOOGLE_ID`
  / `AUTH_GOOGLE_SECRET` (the dashboard's Google OAuth client, with our callback added as a redirect
  URI — see Open questions), **`OMT_GATEWAY_DISABLED: "true"`** (dark even if applied). Service
  ClusterIP `mcp-server:8000`. NetworkPolicy `mcp-server-allow-cloudflared` (ingress to 8000 from
  `app: cloudflared` only). NetworkPolicy `tenant-dashboard-bff-allow-mcp-server` (sibling of the
  flow-collector policy in `67-flow-collector.yaml`: additive, its own object, so
  `58-tenant-dashboard-bff.yaml` is untouched). Header comment: first-time create is manual, never
  applied by CI until bootstrapped; `kubectl apply -f infra/k8s/68-mcp-server.yaml`.
- `deploy.yml`: `mcp-server` in `SERVICES`, in the dispatch options, `services/mcp-server/*)
  want[mcp-server]=1` and `infra/k8s/68-mcp-server.yaml) want[mcp-server]=1` in the diff case, and
  a bootstrap-guard case (`manifest="infra/k8s/68-mcp-server.yaml"`). Not in `RESTART_ONLY` (the
  manifest is the source of truth; live overrides are only `OMT_GATEWAY_DISABLED`, so the runbook
  says the enable flip must ALSO be made in the manifest or a later roll re-darkens it — the
  darkpool-collector precedent). `build-images.yml`: matrix row `service: mcp-server, module: "",
  dockerfile: services/mcp-server/Dockerfile, cache_scope: mcp-server`. `ci.yml`: add a `postgres:16`
  service to the `mcp-server` job and export `OMT_TEST_DATABASE_URL` so the `PostgresStore` tests
  run.
- `secrets.template.yaml` (`dashboard-secrets`): `MCP_GATEWAY_DB_PASSWORD`, `MCP_GATEWAY_SIGNING_KEY`
  (PEM), `BFF_MCP_DELEGATED_PUBLIC_KEY` (if not already added in PR 1), with generation recipes.
- Cloudflare (operator, out of band, documented in `docs/ops/mcp-remote-gateway-runbook.md`): DNS
  CNAME `mcp.<domain>` → tunnel; tunnel ingress rule `mcp.<domain>` →
  `http://mcp-server.copytrade.svc.cluster.local:8000`; NOT added to the dashboard's Access
  application; a WAF managed ruleset and a rate-limit rule (e.g. 120 req/min per IP on `/token`,
  `/register`, `/authorize`; 600 req/min on `/mcp`); no IP allowlist (AI clouds). Kill switch =
  `kubectl set env deploy/mcp-server OMT_GATEWAY_DISABLED=true` (503 within one pod restart) and,
  for a hard cut, delete the ingress rule.

**F. Docs (PR 6).** `docs/ops/mcp-client-compat.md` and the README section, see Done when.

### Testing Decisions

- Behaviour at boundaries only: HTTP status/headers/bodies, which tenant a handler sees, what the
  MCP client receives. No test reaches into hashing, JWS assembly or SQL helpers except through
  those seams. Every security guard is mutation-checked once by hand: break it, run the full suite
  (`mvn test` for Java, never `surefire:test`; `uv run pytest -q` for Python), see the named test
  red, restore. Each step's table names guard, break, and the test that must go red.
- Seams:
  - **A** (existing) `ServiceTokenFilter.doFilter` with mock servlet objects. Catches every status
    decision and the downstream headers/attribute. Misses what a handler does with them.
  - **C'** (new, mirrors `McpWhoamiEndToEndWebMvcTest`) `@WebMvcTest({McpWhoamiController.class,
    TradesController.class})`, filters ON, `bff.mcp-delegated.enabled=true` + test public key via
    `@TestPropertySource`, `@MockitoBean DashboardMembershipReader`. Catches filter → authenticator
    → wrapper → `TenantContext` → handler. Misses the real SQL.
  - **K** (new) `DashboardMembershipReaderIT` + `McpOauthMigrationIT` (Testcontainers, `RUN_DB_ITS`,
    runs in CI's `mvn verify`). Catches the SQL, the grants, the PG16 column rule. Misses nothing
    else about the DB.
  - **D'** `@WebMvcTest` slices for the grants controllers (PR 4). Catches identity binding, 404 on
    foreign grant, dark flags.
  - **H** (new) `httpx.AsyncClient(transport=httpx.ASGITransport(app))` against
    `build_gateway_app(...)` with `InMemoryStore`, `FakeIdp`, a test Ed25519 key and the stub BFF
    (extended to verify the assertion's signature and claims with the test public key and to 403 on
    tenant mismatch, mirroring PR 1). Catches the entire OAuth surface, tenant mapping, redaction,
    rate limits, kill switch. Misses uvicorn and a real MCP client.
  - **I** (new) real `mcp.Client` over `streamable_http_client` against uvicorn on an ephemeral
    `127.0.0.1` port (thread, like `test_stdio_e2e`), bearer obtained via Seam H's flow. Catches
    transport framing, `Host` allow-list, `stateless_http`. Misses TLS and Cloudflare.
  - **J** `PostgresStore` against `OMT_TEST_DATABASE_URL` (skipped when unset). Catches the SQL ⇄
    V17 agreement.
  - **E/F/G** (existing stdio seams) stay green and unchanged in meaning.
  - **L** dashboard `lib/*.test.mjs` for the pure helper; `lint` + `typecheck` for the page.
- Expected hashes, JWS strings and worked numbers are produced outside the code under test
  (`sha256sum`, PyJWT in the test, a generated test key pair), never by the production helper.
- Prior art: `ServiceTokenFilterTest`, `McpWhoamiEndToEndWebMvcTest`, `DashboardWriterMigrationIT`,
  `DashboardMigrationSqlStructureTest`, `ProvisioningBindControllerWebMvcTest`, mcp-server
  `test_startup` / `test_stdio_e2e` / `everyTool_isRedacted`, dashboard `lib/darkpool.test.mjs`.

### Out of Scope

Everything under Non-goals, plus: DPoP / mTLS-bound tokens, token introspection, PAR, a second
replica (needs a shared rate-limit store), e-mail notifications on new grants, Facebook identities
in the consent flow, and migrating the local stdio token to OAuth.

### Further Notes

- `dashboard_user` is keyed `(provider, subject, tenant_id)`; emails are informational. Nothing in
  this plan keys on email; the consent page shows no email.
- `TenantContext.TENANT_ID` is `[A-Za-z0-9_-]+`; the assertion's `tenant` claim and the gateway's
  `tenant` tool argument use the same pattern so a value that passes the gateway cannot fail the BFF
  for charset reasons.
- The stdio stub BFF today checks a static bearer; after 3a it accepts either the static token or a
  valid assertion. The stdio tests do not change.
- `services/mcp-server/*` currently falls into `deploy.yml`'s `*) : ;;` (no rollout). After PR 5
  every merge touching it rolls `mcp-server` once bootstrapped, which is correct and dark until the
  operator flips `OMT_GATEWAY_DISABLED`.

## Steps

### Step 1 — BFF: delegated-assertion credential class, inert by default (PR 1)

**Goal:** With `bff.mcp-delegated.enabled=true` and a public key configured, a valid EdDSA assertion
for a `(google, sub, tenant)` that exists in `dashboard_user` reads the allowlist as that tenant and
nothing else; with the flag off (default) nothing changes for anyone.

**Blocked by:** none.

**Seams:** A (filter unit tests with mock servlet objects and a jOOQ `MockDataProvider`-backed
`DashboardMembershipReader` returning one row / no rows / throwing); C' (filters-ON slice with the
reader mocked); K (`DashboardMembershipReaderIT`, Testcontainers: V1–V16 applied, insert a row as
superuser, `isMember` true/false, and a SELECT as `dashboard_readonly` succeeds while an
`UPDATE` fails).

**Files:** `security/ServiceTokenFilter.java`, `security/DelegatedAssertionAuthenticator.java` (new),
`security/DashboardMembershipReader.java` (new), `config/DataSourceConfig.java`,
`src/main/resources/application.yml`, `security/ServiceTokenFilterTest.java`,
`security/DelegatedAssertionTestKeys.java` (test helper: generates an Ed25519 pair, builds
assertions with PyJWT-equivalent manual JWS assembly using `java.security`, independent of the
production class), `web/McpDelegatedEndToEndWebMvcTest.java` (new), `db/DashboardMembershipReaderIT.java`
(new), `infra/k8s/58-tenant-dashboard-bff.yaml`, `infra/secrets-template/secrets.template.yaml`.

**Tests to add (Seam A unless noted):**

1. `delegated_valid_allowlistedGet_passesAsBoundTenant` — reader → true; `GET /api/positions`;
   downstream `X-Tenant-Id == acme`, no `X-Operator-Id`, attribute `dlg-` + 12 hex of
   `sha256("google:sub-1")` (literal computed with `sha256sum`).
2. `delegated_membershipAbsent_is403` — reader → false; chain not invoked.
3. `delegated_readerThrows_is403` — reader throws; chain not invoked (fail-closed).
4. `delegated_expired_is401`, `delegated_iatInFuture_is401`, `delegated_lifetimeOver300s_is401`.
5. `delegated_wrongAudience_is401`, `delegated_wrongIssuer_is401`, `delegated_wrongProvider_is401`,
   `delegated_badTenantCharset_is401`.
6. `delegated_wrongKey_is401` (signed with a second pair), `delegated_tamperedPayload_is401`.
7. `delegated_algNone_is401`, `delegated_algHS256_is401` (an HMAC over the public key bytes, the
   classic confusion), `delegated_algRS256Header_is401`.
8. `delegated_writeMethods_are403`, `delegated_nonAllowlistedGet_is403`,
   `delegated_forgedOtherTenantHeader_is403`, `delegated_sameTenantHeader_isAllowed`.
9. `delegated_onIngestRoutes_is401` (rule 3 still wins), `delegated_encodedPaths_are401` (rule 1).
10. `delegated_disabled_is401` — filter built with `null` authenticator; the valid assertion → 401
    `{"error":"unauthorized"}`.
11. `staticMcpToken_stillWorks_whenDelegatedEnabled` and `sharedToken_unchanged_whenDelegatedEnabled`.
12. `authenticator_enabledWithEmptyKey_failsBoot`, `authenticator_malformedKey_failsBoot` (direct
    construction → `IllegalStateException`).
13. Seam C': `whoami_withDelegatedAssertion_returnsDlgBinding` (strict JSON
    `{"tenant_id":"acme","token_id":"dlg-…","scope":"read"}`), `trades_withDelegatedAssertion_readsAsAcme`
    (reader mock called with `("google","sub-1","acme")`), `whoami_membershipAbsent_is403`,
    `existing sliceTests unchanged` (the McpWhoami end-to-end class is untouched; evidence by diff).
14. Seam K: `isMember_trueForExistingRow_falseOtherwise`, `readonlyRole_cannotUpdateDashboardUser`.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `mvn -B -ntp test` → `BUILD SUCCESS`; `ServiceTokenFilterTest` `Tests run: ≥ 92, Failures: 0`;
      `McpDelegatedEndToEndWebMvcTest` green; `ApplicationContextSmokeTest`,
      `ApplicationContextWriterEnabledSmokeTest`, `SpringComponentConstructorGuardTest` green —
      evidence: the `Tests run:` lines.
- [ ] `RUN_DB_ITS=true mvn -B -ntp -pl services/tenant-dashboard-bff verify` → `BUILD SUCCESS` with
      `DashboardMembershipReaderIT` listed — evidence: its `Tests run:` line (if Docker is
      unavailable locally, record that and rely on CI's run; the step is not complete until CI
      shows it green).
- [ ] `git diff d3f02dac -- …/ServiceTokenFilterTest.java` shows only `, null)` on the 14 existing
      constructor calls plus additions; `git diff … -- …/McpWhoamiEndToEndWebMvcTest.java` is empty —
      evidence: the diff stats.
- [ ] `grep -c 'public ServiceTokenFilter(' …` = 1; the two new components have one constructor each
      — evidence: grep counts.
- [ ] The static-token log line is byte-identical to before (`mcp read tenant={} token_id={}
      method={} path={}`); the delegated line adds `client_id={}` and contains no `sub` — evidence:
      the two format strings in the diff and a pasted log line from test 1.
- [ ] Mutation table, each row seen red then restored (evidence per row: failing test name +
      assertion message, then the green rerun):

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | signature | skip `Signature.verify` | tests 6 |
      | alg pin | accept any `alg` | tests 7 |
      | exp | drop the `exp > now` check | test 4a |
      | lifetime cap | drop `exp − iat ≤ 300` | test 4c |
      | aud / iss / provider | drop each compare | tests 5 |
      | tenant charset | drop the regex | test 5d |
      | membership | return 200 when reader false | test 2, C' 13c |
      | fail-closed reader | catch and treat exception as member | test 3 |
      | shared read rules | call `chain.doFilter` directly instead of `applyMcpReadRules` | tests 8 |
      | rule order | consult the authenticator before rule 3 | test 9a |
      | inert when disabled | treat `null` authenticator as "accept" | test 10 |
      | boot check | accept an empty key | test 12 |
      | credential id | hard-code the attribute | test 1 (attribute literal) |

- [ ] `application.yml` has the three new property blocks with comments; `58-tenant-dashboard-bff.yaml`
      gains `BFF_MCP_DELEGATED_PUBLIC_KEY` (`optional: true`) and `DASHBOARD_READONLY_URL`, and does
      NOT set `BFF_MCP_DELEGATED_ENABLED`; `secrets.template.yaml` gains the key with the derive
      recipe — evidence: the diff hunks; YAML parses.
- [ ] `spotless:apply` then `spotless:check` on the module → `BUILD SUCCESS` — evidence: output.
- [ ] `git diff --stat d3f02dac` lists only the files named under **Files** plus this plan —
      evidence: stat output.

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] Any pre-existing `ServiceTokenFilterTest` assertion must change beyond the `null` argument, or
      any other BFF test must be weakened or deleted.
- [ ] Verifying EdDSA needs a new Maven dependency (JDK 21 `Ed25519` must suffice).
- [ ] A second constructor appears on any component, or `TenantContext` / `GlobalExceptionHandler` /
      any controller needs a change.
- [ ] The filter needs the membership reader to be non-null in an existing `@WebMvcTest` slice (an
      existing slice test changes).
- [ ] A disabled gateway class lets any assertion through, or a static token's behaviour changes.
- [ ] The membership query needs a grant beyond V2's `SELECT ON dashboard_user` to
      `dashboard_readonly`, or needs the writer datasource.
- [ ] A mutation row cannot be made red with the planned test.

### Step 2 — Dashboard-DB migrations and the least-privilege `mcp_gateway` role (PR 2)

**Goal:** The six `mcp_oauth_*` tables exist with grants for `dashboard_readonly` (list) and
`dashboard_writer` (revoke); the `mcp_gateway` role exists with CRUD on those tables and exactly a
three-column SELECT on `dashboard_user`; every grant is proven against real Postgres.

**Blocked by:** none (independent of Step 1; PR 3 and PR 4 depend on it).

**Seams:** K (`McpOauthMigrationIT`, Testcontainers `postgres:16`, Flyway with all four placeholders;
then `SET ROLE` / reconnect as each role and run the exact statements the gateway and BFF will run);
`DashboardMigrationSqlStructureTest` additions (static assertions on the SQL text so a reviewer
without Docker sees the shape).

**Files:** `db/dashboard/V17__mcp_oauth.sql`, `db/dashboard/V18__mcp_gateway_role.sql`,
`application.yml` (placeholder `mcp_gateway_password: ${MCP_GATEWAY_DB_PASSWORD}` + comment),
`db/McpOauthMigrationIT.java` (new), `db/DashboardMigrationSqlStructureTest.java` (additions),
`infra/k8s/58-tenant-dashboard-bff.yaml` (`MCP_GATEWAY_DB_PASSWORD` secretKeyRef, required),
`infra/secrets-template/secrets.template.yaml` (key + `openssl rand -base64 32` recipe),
`docs/ops/mcp-read-token-runbook.md` or the new gateway runbook stub: the pre-merge operator step.

**Tests to add (Seam K unless noted):**

1. `tablesAndIndexesExist` — six `to_regclass` checks, the `(provider, subject)` index, the FKs.
2. `gatewayRole_canReadMembershipColumnsOnly` — as `mcp_gateway`: `SELECT provider, subject,
   tenant_id FROM dashboard_user WHERE provider = ? AND subject = ?` succeeds; `SELECT email FROM
   dashboard_user` → `42501`; `SELECT * FROM dashboard_user` → `42501`; `SELECT 1 FROM
   dashboard_user_invite` → `42501`; `INSERT/UPDATE/DELETE dashboard_user` → `42501`.
3. `gatewayRole_hasCrudOnOauthTables_includingOnConflictAndUpdateWhere` — the real statement
   shapes: `INSERT … mcp_oauth_client … ON CONFLICT (client_id) DO UPDATE SET …`, `UPDATE
   mcp_oauth_refresh_token SET rotated_at = now(), replaced_by_hash = ? WHERE token_hash = ? AND
   rotated_at IS NULL`, `UPDATE mcp_oauth_grant SET revoked_at = now() WHERE id = ?`, `DELETE FROM
   mcp_oauth_client WHERE expires_at < now() AND last_used_at IS NULL`, `SELECT … FROM
   mcp_oauth_access_token t JOIN mcp_oauth_grant g ON … WHERE t.token_hash = ? AND g.revoked_at IS NULL`.
4. `readonlyRole_canSelectGrantsAndClients_cannotWrite` — SELECT ok on both; `UPDATE mcp_oauth_grant`
   → `42501`; SELECT on `mcp_oauth_refresh_token` → `42501`.
5. `writerRole_canRevokeGrant_cannotInsertOrTouchTokens` — the PR 4 `UPDATE … WHERE id = ? AND
   provider = ? AND subject = ?` succeeds (1 row), `INSERT INTO mcp_oauth_grant` → `42501`, SELECT on
   `mcp_oauth_access_token` → `42501`.
6. `existingRoles_unchangedElsewhere` — `dashboard_readonly` still cannot read
   `dashboard_user_invite` beyond V6, `dashboard_writer` still cannot SELECT `dashboard_user`
   columns other than `tenant_id` (V7) — re-asserts the invariants `DashboardWriterMigrationIT`
   already holds, in this class, so a widening here is caught.
7. Structure test: `v17CreatesSixTablesAndGrantsOnlyReadToReadonlyAndUpdateToWriter`,
   `v18CreatesGatewayRoleWithPlaceholderPasswordAndColumnLevelSelect` (asserts the literal `GRANT
   SELECT (provider, subject, tenant_id) ON dashboard_user TO mcp_gateway` and the absence of any
   `GRANT … ON dashboard_user_invite TO mcp_gateway`).

**Success checklist:**

- [ ] `RUN_DB_ITS=true mvn -B -ntp -pl services/tenant-dashboard-bff verify` → `BUILD SUCCESS`;
      `McpOauthMigrationIT` `Tests run: 6, Failures: 0`; `DashboardWriterMigrationIT` and the other
      `*MigrationIT` unchanged and green — evidence: `Tests run:` lines (CI run acceptable if no
      local Docker; not complete until CI green).
- [ ] `mvn -B -ntp test` (no ITs) → `BUILD SUCCESS`; `ApplicationContextSmokeTest` still boots with
      the new placeholder unset (proves tests do not run Flyway, as with
      `DASHBOARD_READONLY_PASSWORD`) — evidence: its `Tests run:` line.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | column-level select | `GRANT SELECT ON dashboard_user TO mcp_gateway` (table-level) | test 2 (`email` SELECT succeeds) and 7b |
      | no invite access | add `GRANT SELECT ON dashboard_user_invite TO mcp_gateway` | test 2, 7b |
      | gateway CRUD | drop `UPDATE` on `mcp_oauth_refresh_token` | test 3 |
      | readonly stays read | add `UPDATE` to the readonly grant | test 4 |
      | writer minimal | add `INSERT` on `mcp_oauth_grant` to writer | test 5 |
      | password placeholder | literal password in V18 | test 7b |

- [ ] V18 uses `IF NOT EXISTS` + `${mcp_gateway_password}` and dynamic `GRANT CONNECT ON DATABASE
      %I` exactly as V5 — evidence: the file and test 7b.
- [ ] `58-tenant-dashboard-bff.yaml` gains `MCP_GATEWAY_DB_PASSWORD` from `dashboard-secrets`
      (NOT optional: Flyway needs it) with a comment naming the pre-merge operator step;
      `secrets.template.yaml` gains the key — evidence: diff hunks; YAML parses.
- [ ] The pre-merge operator step is written down (runbook stub) and the PR description carries it
      as a merge blocker — evidence: the doc section and the PR body text.
- [ ] `spotless:apply` / `spotless:check` → `BUILD SUCCESS`; `git diff --stat` lists only the named
      files — evidence: outputs.

**Failure checklist:**

- [ ] A needed statement fails as `mcp_gateway` and the only fix is a table-level `SELECT` on
      `dashboard_user` or any privilege on `dashboard_user_invite`.
- [ ] `DashboardWriterMigrationIT` or `DashboardMigrationSqlStructureTest` existing assertions must
      change.
- [ ] A shipped migration (V1–V16) needs editing.
- [ ] The placeholder needs a default value to keep any test or the smoke test green.
- [ ] Flyway runs in a unit-test context and the new placeholder breaks it (report; do not add a
      default).

### Step 3a — Gateway: Streamable HTTP mode as a resource server, per-request tenant scope (PR 3)

**Goal:** `build_gateway_app` serves `/mcp` over Streamable HTTP; a request without a bearer gets
401 + `WWW-Authenticate` pointing at our protected-resource metadata; a request with an opaque token
the store knows (seeded directly in this step) resolves to the grant's subject and tenants,
re-checks membership, and the seven tools answer through a per-request `BffClient` carrying an
EdDSA assertion; `tenant` argument semantics hold; stdio mode is unchanged.

**Blocked by:** none for code (the stub BFF mimics PR 1 and `InMemoryStore` stands in for PR 2);
PR 3 merges after PR 1 and PR 2.

**Seams:** H (ASGI transport, no AS routes yet: `token_verifier=StoreTokenVerifier`), I (uvicorn +
`mcp.Client`), E/F/G unchanged.

**Files (new unless noted):** `ohmytradeagent_mcp/gateway/{__init__,__main__,settings,app,store,
verifier,resolver,assertion,audit,main}.py`, `ohmytradeagent_mcp/tools.py` (`build_server(scope,…)`
+ `tenant` param on every tool), `ohmytradeagent_mcp/bff.py` (token provider), `ohmytradeagent_mcp/main.py`
(constant scope), `pyproject.toml` (`uvicorn`, `pyjwt[crypto]`; `omt-mcp-gateway` script), `uv.lock`,
`tests/conftest.py` (stub BFF accepts a valid assertion; fixtures for test keys, `InMemoryStore`
seeding helpers), `tests/gateway/{test_http_auth,test_tenant_scope,test_tools_over_http,
test_e2e_streamable_http,test_killswitch_health}.py`, existing tests: mechanical `build_server` call
updates only.

**Tests to add:**

1. `mcp_withoutBearer_is401_withResourceMetadataHint` — header value contains
   `resource_metadata="<public>/.well-known/oauth-protected-resource/mcp"`; both metadata paths
   (with and without `/mcp`) return the document with `authorization_servers == [<public>]`.
2. `mcp_unknownToken_is401`, `mcp_expiredToken_is401`, `mcp_revokedGrant_is401`.
3. `mcp_wrongResourceIndicator_is401`, `mcp_absentResource_isAccepted`.
4. `oneTenantGrant_tenantImplicit` — `get_portfolio_summary()` with no `tenant` → `tenant_id == "acme"`;
   stub BFF saw `X-Tenant-Id: acme` and an assertion with `tenant == "acme"`, `sub == "sub-1"`,
   `client_id`, `exp − iat == 60`, `aud`, `iss`.
5. `twoTenantGrant_requiresTenantArg` — without → `is_error`, text contains `tenant required` and
   neither tenant id; with `tenant="beta"` → `tenant_id == "beta"`; with `tenant="gamma"` (not in
   grant) → `is_error`, text contains `gamma` and `not authorised`, not `acme`/`beta`.
6. `membershipRemovedMidSession_nextCallRefused` — remove `(google, sub-1, acme)` from the fake
   membership → next tool call 401 at HTTP (token invalid) for a one-tenant grant; for a two-tenant
   grant the remaining tenant still works and the removed one is `not authorised`.
7. `everyTool_isRedacted_overHttp` — iterate `list_tools()` (7) through the HTTP client against the
   fixture set already used by the stdio sweep; no `account_number`, `broker_order_id`,
   `operator_id`, email literal in any output.
8. `everyBffCall_carriesFreshAssertion_andTenantHeader` — the stub counts assertions; two tool calls
   → two distinct `jti`-less tokens with different `iat` or equal `iat` but ≤ 60 s lifetime; a tool
   call after the stub starts rejecting (403) → `ToolError` naming the BFF status.
9. `auditLine_perToolCall` — captured stderr has exactly one `mcp tool subject_hash=… client_id=…
   tenant=acme tool=get_portfolio_summary outcome=ok` line; no `sub-1`, no token, no `@`.
10. `killSwitch_returns503_everywhereButHealthz`; `readyz_503_whenStoreDown`; `healthz_200`.
11. `hostNotAllowed_is421or400` — request with `Host: evil.example` → rejected by transport security;
    with the public host → served.
12. Seam I: `streamableHttp_realClient_listsSevenToolsAndOnePrompt_andCallsOne` — uvicorn thread,
    `mcp.Client(streamable_http_client(url, http_client=httpx2 client with bearer))`.
13. Stdio: `stdio_tenantArgMismatch_isToolError`, `stdio_tenantArgMatch_isAllowed`; all existing
    stdio tests green unchanged in assertions.
14. Settings: `gateway_missingPublicUrl_exits2_namesIt` (Seam F style, `run_gateway(env)`).

**Success checklist:**

- [ ] `uv sync --locked && uv run ruff check . && uv run pytest -q` → green; count ≥ previous + 20 —
      evidence: output; `uv tree --depth 1` shows `uvicorn`, `pyjwt`, `mcp v2.3.0`.
- [ ] `git diff d3f02dac -- services/mcp-server/tests/test_*.py` shows only `build_server(` call-site
      changes in the pre-existing tests — evidence: the diff.
- [ ] Every tool signature ends with `tenant: str | None = None` and `list_tools()` schemas show it
      as optional — evidence: test 5's schema assertion.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | token lookup | accept any bearer | test 2a |
      | expiry | drop `expires_at > now` | test 2b |
      | revoked grant | drop the `revoked_at IS NULL` join | test 2c |
      | resource | accept a mismatching `resource` | test 3a |
      | membership re-check | skip the per-request `dashboard_user` check | test 6 |
      | tenant in grant | accept any `tenant` arg | test 5c |
      | no tenant leak | include the grant's tenants in the error text | test 5a/5c |
      | assertion claims | omit `tenant` from the JWS | test 4 (stub 403 → ToolError) |
      | assertion lifetime | `exp = iat + 3600` | test 4 |
      | audit | log the raw `sub` | test 9 |
      | kill switch | serve `/mcp` when disabled | test 10a |
      | host allow-list | pass `transport_security=None` | test 11 |

- [ ] `uv run omt-mcp` stdio behaviour unchanged: `test_startup` and `test_stdio_e2e` green; stub BFF
      still accepts the static test token — evidence: test output.
- [ ] No `print(` to stdout in the gateway package (`grep -rn "print(" ohmytradeagent_mcp/gateway`
      shows only `file=sys.stderr` or none) — evidence: grep.

**Failure checklist:**

- [ ] `get_access_token()` is not populated inside tool functions under `stateless_http=True` (then
      report; do not fall back to parsing the header inside tools).
- [ ] The SDK's `RequireAuthMiddleware`/`BearerAuthBackend` cannot be given our verifier without
      touching SDK private attributes.
- [ ] A tool's data contract must change beyond adding `tenant` to make the HTTP path work.
- [ ] Tests need to mock `BffClient`, the store or the signer rather than drive them through the
      ASGI/stub seams (`InMemoryStore` and `FakeMembership` are the planned test doubles at the
      store boundary; nothing inside the provider is mocked).
- [ ] `mcp==2.3.0` must be bumped.

### Step 3b — Gateway: OAuth authorization server federating to Google (PR 3)

**Goal:** A client discovers the AS from a 401, registers (DCR) or presents a CIMD URL, is sent
through Google, the member consents, and PKCE + code + rotating refresh tokens work per spec with
reuse detection; non-members are refused at sign-in; the SDK's `auth_server_provider` wiring
replaces 3a's bare verifier with no behaviour change at `/mcp`.

**Blocked by:** Step 3a.

**Seams:** H (full flow with `FakeIdp` + `InMemoryStore` + a `fetch_cimd` test double), I (the
bearer for the e2e test now comes from the flow).

**Files (new unless noted):** `gateway/provider.py`, `gateway/google.py` (`IdP` protocol:
`authorize_url(state, nonce) -> str`, `exchange(code, nonce) -> Identity(sub, email_verified)`;
`GoogleIdp` with JWKS verification via `PyJWKClient`), `gateway/consent.py`, `gateway/cimd.py`,
`gateway/redirects.py` (policy), `gateway/app.py` (AS routes, callback + consent custom routes,
metadata override), `tests/gateway/{test_metadata,test_dcr,test_cimd,test_authorize_google,
test_consent,test_token_pkce,test_refresh_rotation,test_revoke}.py`, `tests/gateway/fake_idp.py`.

**Tests to add:**

1. `asMetadata_advertisesWhatClientsNeed` — `code_challenge_methods_supported == ["S256"]`,
   `registration_endpoint`, `revocation_endpoint`, `client_id_metadata_document_supported is True`,
   `grant_types_supported ⊇ {authorization_code, refresh_token}`, `issuer == <public>`.
2. DCR: `register_httpsRedirect_ok_jsonBody`, `register_loopbackRedirects_ok`
   (`http://127.0.0.1:53280/callback`, `http://localhost:7777/oauth2callback`, `http://[::1]:1234/`),
   `register_httpNonLoopback_rejected`, `register_wildcardOrFragment_rejected`,
   `register_ipLiteralHttps_rejected`, `register_formEncodedBody_is400`,
   `register_unusedClientExpires_afterSweep` (clock advanced 31 d → `get_client` None).
3. CIMD: `cimd_validDocument_isClientWithAuthMethodNone`, `cimd_clientIdMismatch_rejected`,
   `cimd_httpUrl_rejected`, `cimd_rootPath_rejected`, `cimd_badRedirects_rejected`,
   `cimd_cached_oneFetchPerHour`.
4. Authorize: `authorize_redirectsToGoogle_withStateAndNonce_andNoClientStateLeak`,
   `authorize_unregisteredRedirectUri_isDirectError_noRedirect`, `authorize_plainPkce_isRejected`,
   `authorize_missingCodeChallenge_isRejected`.
5. Callback: `callback_unknownState_is400`, `callback_expiredPending_is400`,
   `callback_nonMember_redirectsAccessDenied_withClientState`,
   `callback_unverifiedEmail_redirectsAccessDenied`, `callback_member_rendersConsent` (200, CSP header
   exact, `X-Frame-Options: DENY`, `Cache-Control: no-store`, body contains escaped `client_name`
   (`<b>x</b>` arrives escaped), the sentence "cannot place, change or cancel orders", one checkbox
   per tenant and no other tenant ids, a hidden `csrf` field).
6. Consent: `consent_wrongCsrf_is400`, `consent_replayCsrf_is400`, `consent_crossSiteFetchSite_is403`,
   `consent_noTenantSelected_is400`, `consent_tenantOutsideSet_is400`, `consent_deny_redirectsAccessDenied`,
   `consent_approve_redirectsWithCodeAndState_andCreatesGrant`.
7. Token: `token_formEncoded_ok_jsonResponse` (`expires_in == 3600`, `refresh_token` present,
   `token_type == "Bearer"`), `token_jsonBody_is400`, `token_missingVerifier_rejected`,
   `token_wrongVerifier_rejected`, `token_codeReuse_rejected_andRevokesGrant`,
   `token_redirectUriMismatch_rejected`, `token_wrongClient_rejected`, `token_expiredCode_rejected`.
8. Refresh: `refresh_rotates_oldTokenDead_newTokenWorks`, `refresh_reuse_revokesWholeGrant`
   (old refresh → `invalid_grant`; the newest refresh and access tokens are also dead; `/mcp` 401),
   `refresh_expired_rejected`, `refresh_otherClient_rejected`.
9. Revoke: `revoke_refreshToken_killsAccessToo`, `revoke_accessToken_killsGrant`,
   `revoke_unknown_is200` (RFC 7009).
10. `mcpAfterFullFlow_works` — the bearer from 7a lists 7 tools; `membership removed → 401` re-run
    through the real provider path.
11. Seam I updated to obtain its bearer through the flow.

**Success checklist:**

- [ ] `uv run ruff check . && uv run pytest -q` → green; the 3a HTTP tests pass unchanged against the
      `auth_server_provider` wiring — evidence: output and an empty diff on `tests/gateway/test_http_auth.py`
      except the fixture that seeds tokens (now via the flow helper).
- [ ] `curl`-equivalent evidence from the ASGI tests pasted for: the AS metadata JSON, a 401
      `WWW-Authenticate` header, a DCR response, a consent page's response headers.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | redirect policy | allow `http://` any host | test 2c |
      | CIMD id match | skip `client_id == url` | test 3b |
      | non-member denied | issue a code for an empty tenant set | test 5c |
      | email_verified | ignore the claim | test 5d |
      | nonce | skip the nonce compare | new `callback_wrongNonce_is400` (add it; the fake IdP returns a wrong nonce) |
      | CSRF | skip the csrf compare | test 6a |
      | CSRF single-use | do not consume | test 6b |
      | tenant subset | accept any posted tenant | test 6e |
      | CSP | drop `frame-ancestors 'none'` | test 5e |
      | code single-use | do not set `consumed_at` | test 7e |
      | PKCE | (SDK-owned; break by passing `code_challenge` through unchanged in `authorize`→code so the stored challenge is wrong) | test 7d |
      | refresh reuse | do not revoke on `rotated_at` set | test 8b |
      | revoke | no-op `revoke_token` | tests 9 |
      | metadata CIMD flag | drop the override | test 1 |

- [ ] The consent HTML is produced by a function with no template engine and all interpolations
      pass through `html.escape` — evidence: `grep -n "escape" gateway/consent.py` and test 5e.
- [ ] No Google network call in tests (`FakeIdp` only); `GoogleIdp` is covered by a test of the
      ID-token verification using a locally generated RSA key as the "JWKS" — evidence: test list.

**Failure checklist:**

- [ ] The SDK's `/authorize` handler cannot be made to carry our `state` → Google → back without
      storing the client's `redirect_uri` (then stop: do not weaken redirect validation).
- [ ] Setting `client_id_metadata_document_supported` requires patching SDK source.
- [ ] A test needs Google, the network, or a real browser.
- [ ] The consent page needs JavaScript to function.
- [ ] Any token or code is stored or logged unhashed.

### Step 3c — Gateway: Postgres store, rate limits, Dockerfile, entrypoint (PR 3)

**Goal:** The gateway persists to the dashboard DB as `mcp_gateway`, throttles per subject / per
client / per IP, builds as an image, and starts from `python -m ohmytradeagent_mcp.gateway` with
one-line failures.

**Blocked by:** Steps 3b and 2 (SQL must match V17).

**Seams:** J (`PostgresStore` against `OMT_TEST_DATABASE_URL`, skipped when unset: the same test
module runs against `InMemoryStore` always and `PostgresStore` when available — a store contract
suite), H (rate limits, IP limiter), process-level (`docker build`, entrypoint exit codes).

**Files:** `gateway/store_postgres.py` (asyncpg; migrations are NOT run here), `gateway/ratelimit.py`,
`gateway/app.py` (limiters wired), `services/mcp-server/Dockerfile`, `.dockerignore` (if needed),
`tests/gateway/{test_store_contract,test_ratelimit}.py`, `pyproject.toml` (`asyncpg`), `uv.lock`,
`README.md` (short "Remote mode" stub; full docs in Step 6).

**Tests to add:**

1. Store contract (parametrised over both stores): client CRUD + sweep; pending auth create/consume/
   expire; grant create/revoke; code consume-once; refresh rotate/reuse flags; access-token lookup
   joins `revoked_at`; `last_used_at` bump; membership lookup returns tenants for `(google, sub)`.
2. `toolRate_perSubject_61stCallInMinute_isRateLimited_withRetryAfterText` (clock injected);
   `toolRate_perClient_isIndependentOfSubject`; `authRate_perIp_31stRegister_is429_withRetryAfter`;
   `authRate_usesCfConnectingIp_whenPresent`.
3. `entrypoint_missingEnv_exits2_oneLine`, `entrypoint_badSigningKey_exits2_oneLine`,
   `entrypoint_disabled_servesHealthzOnly` (subprocess, ephemeral port).

**Success checklist:**

- [ ] `uv run pytest -q` green with `PostgresStore` SKIPPED locally if no DB, and green against a
      local `docker run postgres:16` with V17/V18 applied by the BFF's Flyway (`mvn -pl
      services/tenant-dashboard-bff flyway:migrate` is not configured; apply the two SQL files with
      `psql` after creating the roles) — evidence: output with the skip/pass counts for both modes.
- [ ] `docker build -f services/mcp-server/Dockerfile -t omt-mcp-gateway:test .` succeeds;
      `docker run --rm omt-mcp-gateway:test` → exit 2 and one stderr line naming `OMT_PUBLIC_URL` —
      evidence: output.
- [ ] Mutation table:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | per-subject bucket | key on client only | test 2a |
      | per-IP bucket | key on path only | test 2c |
      | Retry-After | omit the header | test 2c |
      | hashed storage | store the raw refresh token | contract test `refreshToken_isStoredHashed` (reads the table/dict directly: hash present, raw absent) |
      | reuse flag | clear `rotated_at` on rotate | contract test |

- [ ] `uv.lock` committed; `pyproject` pins `mcp==2.3.0` unchanged — evidence: diff.

**Failure checklist:**

- [ ] `asyncpg` cannot run the V17 statement shapes the IT proved (schema drift between the IT's
      SQL and the store: fix the store, never the migration already merged).
- [ ] The Dockerfile needs network at runtime, root, or a model/warm step.
- [ ] The rate limiter needs Redis or a second process to pass a test.

### Step 4 — Dashboard "Connected AI apps" + BFF list/revoke (PR 4)

**Goal:** A signed-in member sees the AI apps connected to their identity (name, created, last used,
tenants) and can revoke one; revocation kills its access and refresh tokens; the operator sees
nobody else's grants; all dark behind flags.

**Blocked by:** Steps 1 (readonly DSL bean) and 2 (tables, grants).

**Seams:** D' (`@WebMvcTest(McpGrantsController.class)` filters OFF with `@MockitoBean` for a
`McpGrantsReader` / `McpGrantsRevoker` pair backed by the two DSLs — the DB boundary — plus one
filters-ON pin in `ServiceTokenFilterTest`), K (`McpGrantsRepositoryIT`: the two statements against
Testcontainers as the right roles), L (`lib/connectedApps.test.mjs`), `npm run lint && npm run typecheck`.

**Files:** BFF `web/McpGrantsController.java`, `web/McpGrantsRevokeController.java`,
`web/IdentityHeaders.java`, `mcp/McpGrantsReader.java`, `mcp/McpGrantsRevoker.java`, tests;
`ServiceTokenFilterTest` (non-allowlisted pin strings); dashboard `auth.ts`, `types/next-auth.d.ts`,
`lib/bff.ts`, `lib/connectedApps.ts` + `.test.mjs`, `app/connected-apps/page.tsx`,
`app/connected-apps/actions.ts`, `components/Nav.tsx` (one gated link).

**Tests to add:**

1. `grants_list_returnsOnlyThisIdentitysGrants_sortedNewestFirst`; `grants_list_missingIdentityHeaders_is401`;
   `grants_list_badSubjectCharset_is400`; `grants_disabled_is404` (flag off slice).
2. `revoke_ownGrant_204_andRevokerCalledWithIdentity`; `revoke_foreignOrUnknownGrant_404`;
   `revoke_writerDisabled_is404`; `revoke_get_is405`.
3. Filter pin: `mcpToken_nonAllowlistedGet_is403` gains `/api/mcp/grants`; new
   `mcpToken_postRevoke_is403`.
4. IT: `listQuery_asReadonly_ok`, `revokeUpdate_asWriter_affectsOnlyOwnRow`.
5. Dashboard: `connectedApps_formatsNeverUsed_andSortsRevokedLast`;
   `connectedApps_rendersSignInAgain_whenIdentityMissing` (helper-level decision function).

**Success checklist:**

- [ ] `mvn -B -ntp test` green with the new slices; `ServiceTokenFilterTest` count +2; `RUN_DB_ITS`
      verify green — evidence: `Tests run:` lines.
- [ ] `cd dashboard && npm run lint && npm run typecheck && npm run test:lib` → 0 errors — evidence:
      output.
- [ ] With `MCP_CONNECTED_APPS_ENABLED` unset: `grep -n "MCP_CONNECTED_APPS_ENABLED" app/connected-apps/page.tsx
      components/Nav.tsx` shows both gates; the page returns `notFound()` — evidence: grep + the code
      path.
- [ ] Mutation table:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | identity binding | ignore the headers and list all | test 1a |
      | revoke ownership | drop `provider/subject` from the WHERE | test 2b and IT 4b |
      | dark flags | remove `@ConditionalOnProperty` | tests 1d, 2c |
      | not for MCP tokens | add `/api/mcp/grants` to `MCP_ALLOWLIST` | test 3 |
      | identity on session | stamp `subject` from `token.email` | type/lint fails + helper test 5b |

- [ ] `spotless` clean; `git diff --stat` limited to the named files.

**Failure checklist:**

- [ ] The list needs the writer datasource or the revoke needs the readonly one.
- [ ] Any identity value is taken from the email rather than `(provider, providerAccountId)`.
- [ ] `TenantContext` must change (the identity helper is new code beside it).
- [ ] The page needs a client component with the BFF token or a direct DB connection from the
      browser bundle.

### Step 5 — Manifests, workflows, Cloudflare runbook (PR 5, workflow-editing: no bot review)

**Goal:** The gateway can be built, applied once by hand, and rolled by CI after that; the BFF
admits it on the network; every operator step to enable, verify, kill and roll back is written
down; nothing is applied.

**Blocked by:** Step 3c (image entrypoint and env names exist).

**Seams:** none in code. Verification = YAML parse, `kubectl apply --dry-run=client` if `kubectl`
is present, `actionlint`/`yq` if present, otherwise `python3 -c 'import yaml…'` on every touched
YAML; a read of `deploy.yml`'s case/guard logic by `bash -n` on the extracted script blocks.

**Files:** `infra/k8s/68-mcp-server.yaml` (new), `.github/workflows/deploy.yml`,
`.github/workflows/build-images.yml`, `.github/workflows/ci.yml` (postgres service for `mcp-server`),
`infra/secrets-template/secrets.template.yaml`, `infra/k8s/README.md` (one bullet),
`docs/ops/mcp-remote-gateway-runbook.md` (new).

**Success checklist:**

- [ ] All touched YAML parses; `kubectl apply --dry-run=client -f infra/k8s/68-mcp-server.yaml`
      lists Deployment, Service, two NetworkPolicies — evidence: output.
- [ ] `68-mcp-server.yaml` has `OMT_GATEWAY_DISABLED: "true"`, replicas 1, `Recreate`, readiness
      `/readyz`, liveness `/healthz`, no `hostNetwork`, `runAsNonRoot: true`, both NetworkPolicies
      with `app: cloudflared` → 8000 and `app: mcp-server` → BFF 8083 — evidence: the file.
- [ ] `deploy.yml`: `mcp-server` appears in `SERVICES`, the dispatch `options`, the diff `case`
      (both `services/mcp-server/*` and `infra/k8s/68-mcp-server.yaml`), and the bootstrap-guard
      `case`; `RESTART_ONLY` unchanged — evidence: `grep -n mcp-server .github/workflows/deploy.yml`.
- [ ] `build-images.yml` has the `mcp-server` row with `dockerfile: services/mcp-server/Dockerfile`
      — evidence: grep.
- [ ] `ci.yml` `mcp-server` job has a `postgres:16` service and `OMT_TEST_DATABASE_URL`; the job's
      first CI run on the PR shows the `PostgresStore` tests as passed (not skipped) — evidence: the
      CI log line with the counts.
- [ ] Runbook sections present: Prerequisites (PR 1–4 merged; `MCP_GATEWAY_DB_PASSWORD` already in
      the BFF from PR 2), Keys (`openssl genpkey -algorithm ed25519`, derive public, both into
      `dashboard-secrets` and `secrets.local.yaml`), Google (add
      `https://mcp.<domain>/oauth/google/callback` to the existing OAuth client), Cloudflare (DNS,
      tunnel ingress via API, WAF, rate-limit rule, explicit "do NOT add to the Access app"), First
      apply (`kubectl apply -f 68`), Enable BFF (`set env BFF_MCP_DELEGATED_ENABLED=true` +
      `BFF_MCP_DELEGATED_PUBLIC_KEY` from secret, rollout restart), Enable gateway (`set env
      OMT_GATEWAY_DISABLED=false` AND the manifest edit so a later roll does not re-darken), Verify
      (`curl` of both metadata docs, `/mcp` 401 header, `/healthz`, `/readyz`, a first sign-in),
      Kill switch (two levels), Rollback (set disabled, remove ingress, disable BFF flag), Blast
      radius (dashboard-only BFF restart; no trading path) — evidence: headings.
- [ ] The PR body states the bot will post `NOT_REVIEWED: edits .github/workflows` and asks for a
      human review; the review stage of this flow is that review — evidence: PR body.
- [ ] No secret value, hostname token or account number in any file; placeholders only — evidence:
      `grep -nE '[0-9a-f]{64}|BEGIN PRIVATE KEY' infra/k8s/68-mcp-server.yaml docs/ops/mcp-remote-gateway-runbook.md`
      returns nothing (the PEM header may appear only in a recipe comment with `<…>`).

**Failure checklist:**

- [ ] The manifest must be applied, or any `kubectl`/`cf` write run, to complete the step.
- [ ] `58-tenant-dashboard-bff.yaml`'s existing NetworkPolicy must be edited (use the additive
      sibling policy instead).
- [ ] `deploy.yml` needs `mcp-server` in `RESTART_ONLY` to be safe (then the manifest is wrong).
- [ ] Any other workflow job must change.

### Step 6 — Client-compat runbook and per-client setup docs (PR 6)

**Goal:** A member or the operator can follow one page per client to connect, knows the OAuth hops
to expect and the callback each client uses, and knows what each provider may retain; the operator
has a results table to fill after enabling.

**Blocked by:** Steps 3b and 5 (hostname, flow, runbook names).

**Seams:** none in code; each command/setting is checked against the client's `--help` or the
vendor docs page (URL + date noted beside it).

**Files:** `docs/ops/mcp-client-compat.md` (new), `services/mcp-server/README.md` ("Remote (OAuth)
mode" section replacing the Step 3c stub; the stdio sections untouched).

**Success checklist:**

- [ ] Sections for: Claude (claude.ai / Desktop / mobile custom connector; callback
      `https://claude.ai/api/mcp/auth_callback`; Desktop managed loopback `127.0.0.1:53280`; CIMD /
      DCR / manual client id), Claude Code (`claude mcp add --transport http <name> <url>`),
      Gemini CLI (`httpUrl`, auto-discovery on 401, loopback `localhost:7777`), Gemini app
      (Connected Apps → custom app; eligibility + Keep Activity caveat), ChatGPT (connectors) —
      evidence: headings.
- [ ] Each section has: setup steps, what the consent page shows, the tenant-selection sentence for
      multi-tenant members (`tenant=` argument), the revoke path (dashboard Connected AI apps), and a
      results row `client | version | date | result | notes` left for the operator — evidence: the
      table.
- [ ] A per-provider data-retention paragraph linking the provider's policy page and stating what
      leaves the cluster (positions, P&L, cash, OCC symbols, author text) and what never does
      (account numbers, broker order ids, operator ids, emails) — evidence: the paragraphs.
- [ ] Every CLI flag quoted was checked against `claude mcp add --help` (version noted) or the
      vendor doc URL (date noted) — evidence: the notes beside each snippet.
- [ ] `git diff --stat` lists only the two docs — evidence: stat.

**Failure checklist:**

- [ ] A client requires a flow this gateway does not offer (e.g. client_credentials, implicit,
      non-PKCE) — report; do not plan a code change here.
- [ ] A claim about a client cannot be sourced to `--help` or a dated vendor page.

## Repo gates

- **Java (PR 1, 2, 4):** `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:apply` before every
  commit; CI runs `spotless:check` per module and the root check. Mutation checks use the full
  `mvn test` (never `surefire:test`, which skips compilation and lets a mutant survive). ITs run
  with `RUN_DB_ITS=true … verify` (CI sets it; local needs Docker). `SpringComponentConstructorGuardTest`:
  one constructor per component. `ApplicationContextSmokeTest` must boot with repo defaults: every
  new property defaults to off/empty except the Flyway placeholder, which follows the proven
  no-default pattern. Spring 2-constructor trap: never add a second constructor.
- **PG16:** `WHERE`/`ON CONFLICT`/`UPDATE … WHERE` columns need SELECT for the executing role;
  table-level SELECT covers it for the `mcp_oauth_*` tables; the column-level grant on
  `dashboard_user` includes exactly the `WHERE` columns. Only the Testcontainers IT catches a miss,
  so Step 2 is not done until CI's `verify` is green.
- **Python (PR 3):** `uv sync --locked`, `uv run ruff check .`, `uv run pytest -q` from
  `services/mcp-server`; `uv.lock` committed; `mcp==2.3.0` exact. Downloaded packages are read, not
  executed as scripts; inspection via `uv run python -I -c`.
- **Dashboard (PR 4):** `npm run lint`, `npm run typecheck`, `npm run test:lib` locally (CI has no
  dashboard job; `build-images.yml` runs `next build` on PR, which type-checks).
- **Deploy:** `tenant-dashboard-bff` is `RESTART_ONLY`: BFF manifest edits document intent; live env
  changes are manual `kubectl set env`. New Deployments need the bootstrap guard + manual first
  apply (darkpool/flow-collector precedent). Workflow-editing PRs get no Claude review
  (`NOT_REVIEWED`): plain CI + human review. `main` has no branch protection.
- **Schema regeneration, Temporal version gates, sidecar RTH rule:** not applicable (no contract
  change, no workflow code, the sidecar image is not rebuilt by these paths).
- **Pre-merge operator prerequisite for PR 2:** `MCP_GATEWAY_DB_PASSWORD` present in
  `dashboard-secrets` and on the live BFF Deployment env, or the BFF crash-loops on the next roll.
- Commits end with the attribution lines the session supplies; the builder never pushes.

## Open questions

1. **Public hostname.** Planned `https://mcp.<domain>` as `OMT_PUBLIC_URL` (assumption:
   `mcp.` under the same apex as the dashboard host). One literal in the manifest and the runbook.
2. **Google OAuth client.** Plan: reuse the dashboard's client (`AUTH_GOOGLE_ID/SECRET` keys) and
   add the gateway callback as a second authorised redirect URI; the Google consent screen then
   shows the dashboard's app name. Alternative: a dedicated client (two new secret keys).
3. **Tool-call rate limit surface.** The PRD says HTTP 429 + `Retry-After`. At the MCP layer the SDK
   has already accepted the POST when a tool runs, so the plan returns a `ToolError` with the
   retry time for per-subject/per-client limits and reserves HTTP 429 for the per-IP limiter on the
   OAuth endpoints (plus Cloudflare's rule). Say so if a true HTTP 429 on `/mcp` is required (it
   would need an ASGI middleware that re-verifies the bearer before the SDK does).
4. **Flyway placeholder with no default (PR 2 deploy hazard).** Plan follows V2/V5 exactly and
   makes the secret a pre-merge operator step. Alternative: create the role out of band and have
   V18 only GRANT inside an `IF EXISTS` guard (silently skips grants if the role is missing). The
   plan prefers fail-closed.
5. **`PostgresStore` test coverage before PR 5.** The Postgres tests skip in CI until PR 5 adds the
   service container; PR 2's IT proves the grants and PR 3's contract suite proves the logic
   against `InMemoryStore`. Acceptable, or should the `ci.yml` change ride in PR 3 (making PR 3 a
   workflow-editing PR without bot review)? Plan: PR 5.
6. **`stateless_http=True, json_response=True`.** Simplest for one replica and every target client;
   if a client turns out to need SSE streaming on `/mcp`, flipping `json_response` is a one-line
   change covered by Seam I.
7. **Opaque vs JWT access tokens.** Plan: opaque (hash lookup, immediate revocation, membership
   re-check per request). JWTs would save a DB read and cost revocation latency.
8. **Lifetimes.** Access 1 h, refresh 30 d, code 5 min, pending auth 10 min, unused DCR client 30 d,
   assertion 60 s (BFF cap 300 s), CIMD cache 1 h. All constants in `gateway/settings.py`.
9. **Identity headers to the BFF (`X-Identity-Provider`/`X-Identity-Subject`).** Plan: trusted
   behind the shared token like `X-Tenant-Id`. Alternative: the dashboard reads grants directly via
   `pg` as `dashboard_readonly` and revokes through the BFF (two paths). Plan keeps one.
10. **Email on the consent page.** Plan: none (subject only, shown as "signed in with Google"). The
    tenant ids are shown because the member must choose among them.
11. **Facebook identities.** `dashboard_user` rows with `provider = 'facebook'` get no MCP access
    (Google-only sign-in per PRD). Noted in the compat doc.
12. **`resource` indicator.** Plan accepts tokens whose `resource` is absent (some clients omit it)
    and rejects a present mismatching one; `validate_token_resource=False` so the SDK does not
    double-check. Tighten to required once all target clients send it.
13. **Who runs the post-enable client-compat matrix.** The operator, after the runbook; PR 6 ships
    the procedure and an empty table.

## Decisions (orchestrator, 2026-11-13 — operator delegated "do it all yourself"; these SUPERSEDE earlier text and checklist items they contradict)

- **D1 (Q4, deploy hazard removed):** the V18 migration creates `mcp_gateway` as **`NOLOGIN` with no password** — there is **no** `mcp_gateway_password` Flyway placeholder and **no** `MCP_GATEWAY_DB_PASSWORD` key in `application.yml`, `58-tenant-dashboard-bff.yaml` or any pre-merge step. Grants are still applied to the role in V18. The operator sets the password at enablement (`ALTER ROLE mcp_gateway LOGIN PASSWORD '<secret>'`, runbook step in PR 5), and the gateway reads it from its own secret. Rationale: a placeholder with no default would crash-loop the BFF (dashboard + operator buttons down) if PR 2 merged before the secret existed. Step 2's IT asserts the role exists, is NOLOGIN until altered, and has exactly the planned grants (and, after `ALTER ROLE … LOGIN PASSWORD` inside the test, can do exactly its job and nothing more). Every Step 2 checklist item that references the placeholder or the BFF env key is replaced by these assertions.
- **D2 (Q1):** public hostname **`tradeagent-mcp.ridopark.com`** (one level under the apex, so Cloudflare Universal SSL covers it; `mcp.tradeagent.ridopark.com` would need an Advanced certificate). `OMT_PUBLIC_URL=https://tradeagent-mcp.ridopark.com`. Not under the dashboard's Cloudflare Access application.
- **D3:** Q2, Q3, Q5–Q13 accepted as the plan assumes. Q5 note: `PostgresStore` tests are env-gated until PR 5 adds the CI postgres service; PR 5 must land (and those tests run green in CI) before any enablement.
- **D4 (from PR 2 review, supersedes Spec lines describing table-level readonly/writer grants):** shipped V17 grants are **column-level**. `dashboard_readonly`: `mcp_oauth_grant (id, client_id, client_name, provider, subject, tenants, created_at, last_used_at, revoked_at)` and `mcp_oauth_client (client_id, metadata, created_at, last_used_at)` — no `*_hash` column, no other OAuth table. `dashboard_writer`: `SELECT (id, provider, subject, revoked_at)` + `UPDATE (revoked_at)` on `mcp_oauth_grant` only. Step 4 must project exactly these columns (no `SELECT *` / jOOQ `selectFrom` on these tables) — its listing shape is already proven under the grants by `McpOauthMigrationIT` (readonly test). `mcp_oauth_grant.client_name` (nullable) holds the app name captured at consent (DCR metadata or the CIMD document); the listing LEFT JOINs `mcp_oauth_client` and falls back to `client_id`. PR 2 has **no** pre-merge operator step (D1); the "Pre-merge operator prerequisite for PR 2" text above is void.
- **D5 (round-3 review finding A — DCR client model):** This gateway registers **public PKCE clients only** — no client secrets, ever. AS metadata advertises `token_endpoint_auth_methods_supported: ["none"]` and `revocation_endpoint_auth_methods_supported: ["none"]`. `/register` body that **omits** `token_endpoint_auth_method` is treated as `"none"` (overrides the SDK's default of `"client_secret_post"`). `/register` body that **explicitly** sets any secret-based method (`"client_secret_post"`, `"client_secret_basic"`, etc.) returns `400 invalid_client_metadata`. `private_key_jwt` is still rejected by the upstream SDK handler. `client_id_metadata_document_supported: true` is preserved. Implemented via `_patch_as_metadata` (two new metadata fields) and `_patch_register_route` (body-normalisation wrapper that rewrites absent/null auth method to `"none"` before delegating to `RegistrationHandler.handle`).
- **D6 (PR 3 review, supersedes Spec text on RFC 8707):** the gateway does **not** reject a bearer whose stored `resource` differs — V17 has no column for it, so the check could only ever fire against the in-memory store. Tokens are bound to this server by being opaque, looked up in this gateway's own store. A `resource` parameter sent at `/authorize`/`/token` (ChatGPT sends one) is accepted and ignored.
- **D7 (PR 3 final review):** the startup DB check is a one-shot connection (`SELECT 1`), never the store's pool — the pool is created on uvicorn's event loop by the ASGI lifespan. Unhandled errors on request paths answer a generic `503 {"error":"temporarily_unavailable"}`; only the exception type is logged. Expired `pending_auth` / `code` / token rows are not swept yet (rows are rejected on use by their expiry checks); a periodic sweep is a follow-up, not part of PR 3.
