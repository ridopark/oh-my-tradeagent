# PLAN — 2026-08-12 `/options-chat` Discord channel mirror

**Goal.** Mirror Discord channel `769790224921395200 / 786109983065505792` — text, links, images,
attachments, embeds — into the dashboard at `/options-chat`, read-only, as close to real time as
the scraping substrate allows, without adding meaningful memory pressure and **without ever putting
the live trading signal feed at risk**.

Scope is display-only: no posting from the frontend, no parsing into trading signals, no Temporal
workflows.

All `file:line` anchors below were re-read at authoring time.

---

## Decisions already made (operator, 2026-08-12)

| # | Decision | Choice |
|---|---|---|
| 1 | Process topology | **Separate pod with its own Chromium.** Not a third tab in `signal-source-discord`. |
| 2 | Storage | **Postgres table + SSE push.** History survives restarts; page loads instantly. |
| 3 | Images/attachments | **Proxy + cache through the backend.** Bytes fetched once at scrape time. |
| 4 | Access | **Any signed-in dashboard user.** |

---

## What we already have (survey)

**The scraper substrate.** `services/signal-source-discord/` is a Playwright **headless Chromium
driving the real Discord web app with a persisted user session** — not a bot token, not `discord.py`.

- Browser launch, one shared `browser` + `context`, memory-hardened:
  `main.py:305-313` (`--disable-dev-shm-usage`, `--disable-gpu`, `--js-flags=--max-old-space-size=384`).
- Session: `context.new_context(storage_state=state/storage_state.json)` (`main.py:313`), captured
  once by the visible-browser flow in `bootstrap.py:119-163` (it injects a webpack hook to recover
  the auth token Discord strips from `localStorage`, `bootstrap.py:49-105`).
- Extraction: `discord_dom.py:168-184` `extract_recent()` → `page.evaluate(_EXTRACT_JS)`; the JS at
  `discord_dom.py:58-95` walks `li[id^="chat-messages-"]`, reads author from
  `h3 span[class*="username"]`, timestamp from `time[datetime]`, and body from
  `div[id="message-content-<snowflake>"]` — **exact** id match (a prefix match caused issue #289).
- Model: `RawMessage(message_id, author, timestamp_iso, content)` (`discord_dom.py:21-27`).
- Two tabs, hardcoded — `DISCORD_CHANNEL_URL` and `DISCORD_WATCHLIST_CHANNEL_URL` are scalars
  (`main.py:92`, `main.py:121`). There is no channel list.
- The watchlist tab owns its own page and self-heals after a renderer crash with bounded backoff
  (`watchlist_watcher.py:272-296`, `:298-386`, `MAX_CONSECUTIVE_CRASHES = 5`) — **this is the
  pattern the new watcher copies.**
- Pod: `infra/k8s/55-signal-source-discord.yaml`, limits `cpu 1000m / memory 2Gi` (`:142-154`),
  raised from 1Gi after the 2-tab Chromium OOMKilled ~2.5×/day and dropped signals.

**Two facts that shape this plan:**

1. **The target channel is in the guild the sidecar is already a member of.**
   `docs/plans/PLAN-watchlist-mirror.html:187` records that `storage_state.json` is a *portable
   user-account session*, and that the account is already a member of prod server
   `769790224921395200` (it ingests `TradingTheTrend` signals there). Channel `786109983065505792`
   is a third channel in that same guild — **no new login, no new invite, no new credential.**
2. **Nothing in the pipeline captures rich content today.** The extractor reads only
   `contentEl.innerText || contentEl.textContent` (`discord_dom.py:87-90`). Attachments, embeds,
   and link hrefs live *outside* `div[id=message-content-…]` and are silently dropped. Rich capture
   is net-new work, not a config change.

**The dashboard.** Next.js 14.2 App Router, TypeScript, all server components with
`export const dynamic = "force-dynamic"`.

- Auth is enforced globally in `dashboard/middleware.ts` — it wraps every route except
  `api/auth`/`_next/*`/`favicon.ico` and redirects to `/signin`. **`/options-chat` is gated with
  zero new code.**
- Data path is the Java BFF over REST: `dashboard/lib/bff.ts:30-50` (`bffGet` injects
  `Authorization: Bearer BFF_TOKEN` + the verified `X-Tenant-Id` from the session).
- `dashboard/lib/db.ts` is a `pg` pool for **auth only**, connecting as the SELECT-only
  `dashboard_readonly` role against `dashboard_user`. New domain data must go through the BFF.
- **There is no SSE and no WebSocket anywhere in the repo.** The established real-time pattern is a
  self-rescheduling `setTimeout` poll from a client island against a route handler:
  `components/LiveProximity.tsx:13-45` (`POLL_MS = 4000`, keeps last good frame on error).
- Styling: Tailwind 3.4, **dark-only**, slate-950/900/800 surfaces. Shared components are flat in
  `dashboard/components/`.
- Nav registration: `dashboard/components/Nav.tsx:6-15` (`LINKS`); note `:26-27` slices the first 4
  into the mobile primary bar, and `components/MobileBottomNav.tsx` keys `ICONS` by href.
- The `dashboard` DB schema is owned by the BFF via Flyway:
  `services/tenant-dashboard-bff/src/main/resources/db/dashboard/` (currently through
  `V8__grant_select_created_at_dashboard_writer.sql`), mirrored by hand for local dev in
  `dashboard/db-init/01-dashboard-schema.sql`.

---

## Architecture

```
 Discord web app (guild 769790224921395200, channel 786109983065505792)
        │  Discord's own WebSocket pushes a new <li> into the DOM
        ▼
 ┌─ discord-chat-mirror (NEW Deployment, SAME image) ──────────────┐
 │  headless Chromium, ONE tab, images/media/fonts BLOCKED         │
 │  MutationObserver ──expose_binding──▶ Python (sub-second push)  │
 │  slow reconcile poll (10s) = safety net for missed mutations    │
 │  rich extractor: text + link hrefs + attachments + embeds       │
 │  httpx fetch of attachment bytes ─▶ WebP transcode (bounded)    │
 └────────────────────┬────────────────────────────────────────────┘
                      │ POST /internal/options-chat/ingest  (bearer)
                      ▼
 ┌─ tenant-dashboard-bff (Java, owns the `dashboard` DB) ──────────┐
 │  INSERT ... ON CONFLICT DO NOTHING  (snowflake PK = idempotent) │
 │  in-process broadcaster ──▶ SseEmitter subscribers              │
 │  GET /api/options-chat/{messages | stream (SSE) | media/{id}}   │
 └────────────────────┬────────────────────────────────────────────┘
                      ▼
 ┌─ dashboard ─────────────────────────────────────────────────────┐
 │  /options-chat  (server shell + "use client" island)            │
 │  /api/options-chat/{messages,stream,media/[id]}  route handlers │
 └─────────────────────────────────────────────────────────────────┘
```

### Why one image, two Deployments

The new watcher is **not** a new service directory. It ships as a second entrypoint inside
`services/signal-source-discord/` (`ohmytradeagent_sidecar/chat_main.py`), deployed as a second
Deployment running the **same image** with an overridden `command`.

This reuses the Dockerfile, the `sidecar` CI job (`.github/workflows/ci.yml:220-240`), the
`build-images.yml` matrix entry, the `storage_state.json` format, and the browser-launch hardening —
instead of duplicating all of it. A new `services/discord-chat-mirror/` directory would be a
straight DRY violation for zero benefit. Per decision #1 the *process* is separate (its own
Chromium, own PVC, own liveness probe); only the *artifact* is shared.

### Why MutationObserver instead of a faster poll

The signal watcher polls `page.evaluate()` every 1.0 s (`watcher.py:181-187`). Polling harder to
chase latency costs CPU linearly and still averages half the interval in lag.

Instead: inject a `MutationObserver` on the message-list container that calls a
`page.expose_binding()` callback whenever Discord's own gateway WebSocket inserts an `<li>`. Latency
becomes *Discord's push latency + one IPC hop* — typically well under 200 ms — while idle CPU drops
to ~zero. A 10 s reconcile `extract_recent()` remains as the safety net for mutations missed during
a tab rebuild or an observer detach.

This is **both** more real-time and cheaper than the existing poll. It is the single most important
design choice in this plan.

### Memory budget

Target limit **1Gi** (request 384Mi), versus the trading sidecar's 2Gi for two tabs:

- One tab, not two.
- `--blink-settings=imagesEnabled=false` plus a `page.route()` interceptor that aborts
  `image`/`media`/`font` requests. **We never need Chromium to render an image** — we read the
  attachment URL out of the DOM and fetch the bytes separately with `httpx`. This is the largest
  single saving and it is only available because we are scraping for URLs, not pixels.
- `--js-flags=--max-old-space-size=256` (lower than the signal tab's 384).
- Periodic tab recycle: Discord's virtualized list still accretes DOM and detached nodes over a
  trading day. Recycle the page on a schedule (6 h) **or** when `document.querySelectorAll('li[id^="chat-messages-"]').length`
  exceeds a threshold, reusing the crash-rebuild path.
- Media transcode is the one unbounded-ish cost: process **one attachment at a time**, skip sources
  over 10 MB, cap decode dimensions, and downscale to max 1600 px WebP. Bounded and serialized.

---

## Security: do NOT store or render Discord's HTML

Tempting shortcut, hard no. `/options-chat` renders **untrusted third-party content** inside an
authenticated dashboard whose server actions can force-exit real-money positions
(`app/live/page.tsx:73` `forceExitAction`, `:108` `trimAction`). Storing `innerHTML` and rendering it
via `dangerouslySetInnerHTML` would be a stored-XSS path from a Discord room we do not control
straight into that session.

Therefore:

- The scraper extracts **plain text plus structured entities** (link hrefs, attachment descriptors,
  embed fields, reply-to id, author, timestamps). Never raw markup.
- The renderer is a small React component that maps a limited Discord-markdown subset
  (`**bold**`, `*italic*`, `` `code` ``, ```` ```block``` ````, `> quote`, `||spoiler||`, links,
  `@mention`, `#channel`, custom emoji) to React elements. No HTML string ever reaches the DOM.
- Every extracted href is scheme-allowlisted (`http`/`https` only) at ingest; anything else is
  rendered as inert text. Outbound anchors get `rel="noopener noreferrer nofollow"` and
  `target="_blank"`.
- Media is served **only** from our own `/api/options-chat/media/{id}` with a fixed
  `Content-Type` derived from our own transcode — never from a stored, attacker-influenced value —
  plus `Content-Disposition: inline` and `X-Content-Type-Options: nosniff`.

Fidelity is very slightly lower than a raw-HTML mirror. That is the correct trade.

---

## Corrections applied after the Phase 1 consults (2026-08-12)

Two specialist reviews (`java-architect` on the BFF, `qa-inspector` on contract/migration coherence)
found four things wrong with this plan as first written. All four are fixed in the shipped Phase 1;
they are recorded here because the later phases inherit them.

1. **The grants targeted the wrong role.** The draft granted `dashboard_readonly` SELECT. That role
   is the Next.js `pg` pool (`dashboard/lib/db.ts`), used only to resolve a login identity to its
   tenants; the BFF has no DSL for it. Reads run as `dashboard_writer` — and granting the
   browser-facing role SELECT on untrusted third-party content would widen a Next.js compromise for
   nothing. V9 grants `dashboard_writer` only.
2. **The feature flag alone would crash the pod at boot.** `dashboardWriterDsl` is itself
   `@ConditionalOnProperty("dashboard.writer.enabled")`, so a single-name gate on
   `options-chat.enabled` yields `NoSuchBeanDefinitionException` at context startup — a BFF that
   will not boot — rather than a quiet 404. Both controllers use the two-name conditional
   (`operator.tenant-invite` precedent) and `OptionsChatWriterDisabledWebMvcTest` pins it.
3. **`BIGSERIAL` would have hit an ungranted sequence.** A non-owner INSERT into a `BIGSERIAL`
   column also needs `USAGE` on its backing sequence — a grant no migration in this DB models (V1
   uses a composite PK, V4 uses `gen_random_uuid()`), so it was very easy to omit and fails only at
   runtime. Child keys are `GENERATED ALWAYS AS IDENTITY`, which removes the failure mode.
4. **Ids are `BIGINT`, not `TEXT`.** Snowflakes stored as text sort lexicographically, which matches
   time order only while every id has the same digit count (Discord crossed 18→19 digits in 2021) —
   a latent wrong-order-at-the-page-boundary bug. They are serialized as JSON *strings* because
   JavaScript loses precision above 2^53.

**The contract JSON schema is deferred, not dropped.** `deploy.yml:185` maps `contract/*` to
`add_all_java`, so adding a schema rolls the live trading orchestrator and exec workers — for a
display-only feature. The BFF does not use generated contract DTOs for HTTP anyway (writes are
`@RequestBody Map<String,Object>` via `RequestBodies`), so the POJO would have been unused, and the
repo's precedent for this exact sidecar→Java HTTP boundary (`CopytradeFanoutController:22-24`) is
explicitly "no contract schema / pydantic". Revisit in Phase 2, where the sidecar is a real consumer
— and merge that change outside market hours.

**The ingest token is NOT `BFF_SHARED_TOKEN`.** The BFF's `ServiceTokenFilter` gates every path with
one credential, so handing it to the chat-mirror pod would let a pod whose entire job is rendering an
untrusted third-party room set any `X-Tenant-Id` and read positions, orders and portfolio for
real-money tenants. `/internal/options-chat/**` is route-scoped to a separate
`OPTIONS_CHAT_INGEST_TOKEN`, and the isolation is asserted in both directions.

---

## Data model (Flyway `V9__options_chat.sql`, `dashboard` DB)

**Shipped as written in `V9__options_chat.sql`** — three tables (`options_chat_message`,
`_attachment`, `_embed`), `BIGINT` snowflake ids, `GENERATED ALWAYS AS IDENTITY` child keys,
`ON DELETE CASCADE`, `bytes BYTEA` left NULL until Phase 4, and one named index
`options_chat_message_channel_id_message_id_idx ON (channel_id, message_id DESC)` matching the read
cursor exactly. Read the migration for the current shape rather than trusting this summary.

**PG16 grant footgun.** The ingest uses `ON CONFLICT DO NOTHING` and the Phase 6 reconcile uses
`UPDATE … WHERE content_hash <> ?`. Per `reference_pg_where_needs_select`, a writer without `SELECT`
on the columns those statements *read* fails at runtime with 42501 — that exact denial is why the
invite bind carries a SAVEPOINT workaround.

V9 grants `dashboard_writer` exactly `SELECT, INSERT` on all three tables — precisely what the
shipped code issues — and **neither UPDATE nor DELETE**. Phase 4 (media fill: `SET bytes`) and
Phase 6 (edit reconcile, retention `DELETE`) each widen in their own migration alongside the code
that needs it, as V5 withheld DELETE until V7 needed it. `OptionsChatMigrationIT` asserts both are
denied, so a later phase has to widen deliberately rather than find the privilege already lying
around. Granting UPDATE up front "because the next phase wants it" is the same speculative widening
the DELETE decision refuses.

Table-level rather than the column-scoped style of V7/V8: those scope columns to keep PII in
`dashboard_user` unreadable, whereas this read endpoint returns whole chat rows by design.

**`dashboard/db-init/01-dashboard-schema.sql` is deliberately NOT updated.** The draft said to
mirror every grant there. That file creates no `dashboard_writer` role (it mirrors only V1–V2 and is
six migrations stale), so a grant would fail with *role does not exist* and abort the whole
`docker-entrypoint-initdb.d` script, breaking local dev bootstrap for everyone. The tables are not
mirrored either: local Next.js never reads them (all `/options-chat` data comes through the BFF,
which creates them via Flyway). A comment records the omission so it does not read as fresh drift.
Fixing the pre-existing V3–V8 drift is out of scope here.

**Storage sizing.** Assume ~200 images/day at ~200 KB raw. Downscaled WebP lands nearer ~40 KB, so
30-day retention ≈ **250 MB** of `bytea`. Comfortable. Without the transcode it would be ~1.2 GB —
the transcode is what makes Postgres-resident media reasonable here.

---

## Contract

Define the ingest payload as a JSON Schema in `contract/schemas/options-chat-message.json`, matching
the repo's existing codegen convention (pydantic on the Python side, jsonschema2pojo on the Java
side — same as `copytrade_signal_payload.py` / `watchlist_mirror_payload.py`). This gives both ends a
typed model and keeps the sidecar↔BFF boundary from drifting, which is exactly the failure class
`qa-inspector` exists to catch.

Note `extra="forbid"` on the generated pydantic models: adding a field later means regenerating both
sides. No Temporal workflow consumes this payload, so **none of this is replay-gated** — a welcome
simplification versus most changes in this repo.

---

## Phases (each an independently shippable PR)

### Phase 1 — Schema + BFF write/read path (dark, no scraper) — SHIPPED

The whole backend, with nothing producing data yet.

- Flyway `V9__options_chat.sql` (tables + grants, above). No `db-init` mirror, no contract schema —
  see the corrections section for why both were dropped.
- `optionschat/OptionsChatRepository` — ingest (idempotent by snowflake, one transaction per batch,
  `content_hash` computed server-side) + the paginated read, both on `dashboardWriterDsl`.
- `optionschat/OptionsChatIngestParser` — the single validation point for the feature: structural
  problems reject the batch (400), content problems are sanitized (non-`http(s)` URLs dropped,
  strings truncated, arrays capped, caller-supplied `content_type` ignored, `channel_id` allowlisted
  to the one configured room).
- `web/OptionsChatController` — `GET /api/options-chat/messages?before=&limit=` (`/api/`, not
  `/internal/`: this repo reserves `/internal/` for service routes). Requires `X-Tenant-Id` for
  authentication but deliberately ignores its value; snowflakes are emitted as strings.
- `web/OptionsChatIngestController` — `POST /internal/options-chat/ingest`.
- `security/ServiceTokenFilter` — route-scopes `/internal/options-chat/**` to
  `OPTIONS_CHAT_INGEST_TOKEN` and rejects the shared token there. It also now rejects any path
  containing a dot segment or percent-encoded dot **before** making any path-based decision:
  `getRequestURI()` is the raw request line while Spring dispatches on the normalized path, so
  `POST /internal/options-chat/%2e%2e/%2e%2e/api/positions` would otherwise have let the scraper's
  token through to a real-money tenant read. The pre-existing `/actuator/` exemption had the same
  shape, unauthenticated. Same approach as Spring Security's `StrictHttpFirewall`; free here because
  no route in this service takes a dot-bearing path segment.
- Flags: `OPTIONS_CHAT_ENABLED` (default false) **and** `DASHBOARD_WRITER_ENABLED`, gating BOTH the
  read and the ingest. Gating only the read would ship a live write sink for untrusted content with
  no consumer — worse than shipping nothing.

**Verified:** `mvn verify` on the module — 280 tests, 0 failures, spotless clean. The structural
migration lock was mutation-tested (a sneaked-in `DELETE` grant and a `TEXT`/`BIGSERIAL` regression
both fail it) so the assertions are not toothless. `OptionsChatMigrationIT` proves the grants against
a real Postgres **as `dashboard_writer`** — the `ON CONFLICT` conflict-probe SELECT, the IDENTITY
insert needing no sequence grant, the `WHERE content_hash <>` predicate SELECT, the paginated read,
and denial of DELETE on all three tables plus any `dashboard_readonly` access.

**Not verified locally:** the IT is gated on `RUN_DB_ITS=true` and Docker is unavailable on this
workstation (Docker Desktop WSL integration off), so it runs in **CI only** — CI is the real gate for
the grant behaviour, exactly as `reference_pg_where_needs_select` predicts.

### Phase 2 — Chat watcher: text, links, reply-to (no media yet)

- `ohmytradeagent_sidecar/chat_dom.py` — rich in-page extractor. Reuses the exact-id content rule
  from `discord_dom.py:87-90` (do not regress #289) and adds: `a[href]` hrefs from within the
  content div, `article[class*="embed"]` fields, reply reference, author avatar, edited marker,
  attachment `<a>`/`<img>` descriptors under the message `<li>`.
- `ohmytradeagent_sidecar/chat_watcher.py` — owns its own page (copy `watchlist_watcher.py:272-296`
  `_new_ready_page` + the `:298-386` bounded-backoff rebuild), installs the MutationObserver via
  `page.expose_binding`, runs the 10 s reconcile, maintains the seen-LRU, POSTs to the BFF.
- `ohmytradeagent_sidecar/chat_main.py` — entrypoint: own browser, own `storage_state`, own
  heartbeat file, request interception blocking `image`/`media`/`font`.
- `infra/k8s/62-discord-chat-mirror.yaml` — Deployment (same image,
  **`command:`** — never `args:`, see below — `strategy: Recreate`, limits `cpu 500m / memory 1Gi`,
  heartbeat liveness probe mirroring `55-*.yaml:133-141`, a 45s `initContainer` stagger, and
  **no `securityContext`**). **No PVC of its own:** an `emptyDir` at `/app/state` owns the heartbeat,
  and the existing `signal-source-discord-state` PVC is mounted read-only at `/app/session` for the
  shared session. Ships an **additive** `NetworkPolicy` granting this pod → BFF:8083.
- `.github/workflows/deploy.yml`: add `discord-chat-mirror` to `SERVICES` (`:100`), to the
  `services/signal-source-discord/*` path mapping (`:179`), and add
  `infra/k8s/62-*.yaml` → same (`:193`).

**Discord DOM fragility.** Class names are hashed and rotate on Discord releases. Every selector
must be attribute/prefix-based (`class*="username"`, `id^="chat-messages-"`) exactly as the existing
extractor does. Add a counter for "message `<li>` seen but no content div matched" and log it — a
silent extraction regression that shows an empty page is the realistic failure mode here, and it
should be loud.

### VERIFIED against the live channel 2026-08-13 (throwaway probe pod, read-only)

A one-off pod on the homelab mounted the shared session read-only and dumped the real DOM. Confirmed:

| Selector | Result |
|---|---|
| `ol[data-list-id="chat-messages"]` | **exists, count 1** — the observer target is real; `<li>`'s parent is `OL.scrollerInner__36d07` with that attribute |
| `div[id="message-accessories-<sf>"]` | **present on 10/10 messages** — the whole attachment path is sound |
| `div[id="message-content-<sf>"]` (exact id) | `content_missing: 0` |
| reply structure | confirmed: two `message-content-*` ids inside one `<li>` |
| `img[class*="avatar"]` | confirmed (`avatar_c19a55 clickable_c19a55`) |

**The finding that matters — do NOT take the attachment URL from `<img src>`.** The real image DOM is:

```
DIV  visualMediaItemContainer_f4758a
 DIV  mosaicItem__6c706 / imageContent__0f481 / imageContainer__0f481
  DIV  imageWrapper_af017a imageZoom_af017a
   A    originalLink_af017a          <- href = the ORIGINAL cdn attachment URL
   IMG  imagePlaceholder_af017a imagePlaceholderVisible_af017a   <- src = PLACEHOLDER
```

With images blocked (which the memory budget requires), the `<img>` that exists is Discord's
**placeholder**, and it carries a `src`. Reading `img[src]` would have stored a page full of
blurhash placeholders instead of charts — and it would have looked like it worked. Take the href from
`a[class*="originalLink"]` (fall back to the wrapping `a[href]`, then `img[src]` last).

**Also learned:** no node carries `width`/`height` attributes, so dimensions must be parsed from the
CDN URL's query params or left null — `dim(img,'width')` yields nothing.

**Still unverified, and honestly so:** `accessories_with_embed: 0` across the sample and no edited
message appeared, so the **embed field selectors and the `[class*="edited"]` marker are untested**.
This room posts uploaded images, not link previews, so embeds are lower-value here — implement them
per the selector table above but treat a zero embed count as expected, not as a regression signal.

**Verify:** unit tests against captured DOM fixtures (mirrors `tests/test_discord_dom.py`, including
the void-`<img>` case at `:171-184`); a staging run shows rows appearing within ~1 s of a live post;
`kubectl top pod` stays under 700 Mi across a full session; killing the tab mid-run rebuilds without
process exit.

### Phase 3 — `/options-chat` page, polling read (first user-visible value)

Deliberately ships **before** SSE and before media, using the proven
`components/LiveProximity.tsx:13-45` polling shape. End-to-end value with zero net-new
infrastructure; SSE then becomes a contained optimization rather than a prerequisite.

- `dashboard/app/api/options-chat/messages/route.ts` — the mandatory server hop (`lib/bff.ts` is
  `import "server-only"`).
- `dashboard/app/options-chat/page.tsx` — server shell (`force-dynamic`, `auth()`, `<Nav/>`).
- `dashboard/components/OptionsChat.tsx` — `"use client"` island: message list, 3 s poll, infinite
  scroll-up via `?before=`, sticky auto-scroll that disengages when the user scrolls up.
- `dashboard/components/DiscordMarkdown.tsx` — the sanitizing renderer described above.
- Register in `Nav.tsx:6-15`; if placed in the first 4 links, add a matching `ICONS` entry in
  `MobileBottomNav.tsx`.

**Verify:** signed-out → redirected to `/signin` by `middleware.ts`; signed-in → live text messages;
a message containing `<img src=x onerror=alert(1)>` renders as literal text (paste it into a dev
channel and confirm); scroll-up paginates; no horizontal overflow at 375 px.

### Phase 4 — Media: fetch, transcode, serve

- Sidecar: after ingest, fetch each attachment with `httpx` (signed CDN URLs are valid ~24 h, so
  fetch promptly), one at a time, skip >10 MB, downscale to ≤1600 px WebP, `PUT` bytes to the BFF.
- BFF: `PUT /internal/options-chat/media/{attachmentId}`, and
  `GET /options-chat/media/{attachmentId}` with a long `Cache-Control: private, max-age=31536000,
  immutable` (content is immutable per id), `nosniff`, and our own content type.
- Dashboard: `app/api/options-chat/media/[id]/route.ts` proxy; renderer shows a blurred
  aspect-ratio placeholder until `fetch_state = 'ok'`, and a "media unavailable" chip on `failed`.

**Verify:** post an image + a PDF + a video to the channel → all three appear correctly; the stored
row's `content_type` matches our transcode, not Discord's header; a >10 MB upload is marked
`skipped_too_large` and renders a link chip rather than breaking the page; sidecar RSS does not
climb across 50 consecutive images.

### Phase 5 — SSE replaces polling

- BFF: in-process broadcaster; on successful ingest, publish to subscribed `SseEmitter`s.
  `GET /options-chat/stream` emits `event: message` frames plus a 20 s `: keepalive` comment.
  Bound concurrent subscribers and drop the slowest on backpressure.
- Dashboard: `app/api/options-chat/stream/route.ts` returns a `ReadableStream` relaying the BFF
  stream. **It must not use `bffGet`** — `lib/bff.ts:18` hardcodes `AbortSignal.timeout(12_000)`,
  which would sever a long-lived stream every 12 s. Write a dedicated streaming fetch.
- Client island: `EventSource` with automatic reconnect; **keep the poll as a degraded fallback**
  after N failed reconnects rather than deleting it.
- Set `X-Accel-Buffering: no` and confirm the stream survives the Traefik ingress **and** the
  Cloudflare Tunnel at `tradeagent.ridopark.com` (`project_homelab_dashboard_public_tunnel`) — an
  edge that buffers would make SSE strictly worse than the Phase 3 poll.

**Verify:** post a message → visible in <1 s over both LAN and the public tunnel; kill the BFF pod →
client falls back to polling and recovers on reconnect; 5 browser tabs open for 30 min leak no BFF
threads.

### Phase 6 — Retention, edits/deletes, ops

- Nightly CronJob (pattern: `infra/k8s/57-audit-completeness-check-cron.yaml`) deleting messages
  older than `OPTIONS_CHAT_RETENTION_DAYS` (default 30); `ON DELETE CASCADE` clears media.
- Reconcile handles **edits** (`content_hash` differs → `UPDATE`, set `edited`) and **deletes**
  (present in a prior scrape, absent now, still inside the visible window → set `deleted_at`;
  render as *"message deleted"*).
- ~~Backfill late-resolving children.~~ **PULLED FORWARD INTO PHASE 2.** Leaving it here would have
  shipped a mirror that renders messages without their charts — in a trading room, the content. It
  needed no migration and no new grant (V9 already grants INSERT on both child tables), so the
  ingest now backfills children onto an existing parent whose child set is empty, and the scraper
  uses a 1.5s settle window after each mutation so most accessories have resolved before the first
  write.
- `docs/ops/options-chat-mirror.md`: session-expiry recovery (mirrors
  `docs/ops/discord-session-expired.md` — scale to 0, bootstrap pod against the new PVC, scale back),
  and the "page is empty / extraction regressed" runbook keyed off the Phase 2 counter.

---

## Phase 2 prerequisites (the NetworkPolicy one turned out NOT to need an operator)

1. **The BFF NetworkPolicy blocks the mirror→BFF hop — solved additively, in code.**
   `infra/k8s/58-tenant-dashboard-bff.yaml:235-252` (`tenant-dashboard-bff-allow-dashboard-only`)
   admits ingress on 8083 only from `app: dashboard`, so the POST is dropped. But **NetworkPolicies
   selecting the same pod are UNIONed, not overridden**, so a second policy
   (`tenant-dashboard-bff-allow-chat-mirror`) shipped *inside* `62-discord-chat-mirror.yaml` grants
   the hop without reading, patching, or re-applying `58-*.yaml` at all. CI applies it automatically
   with the pod, and the grant lives in the same file as its beneficiary so the two cannot drift.
   Do **not** add even a clarifying comment to `58-*.yaml` — any edit there matches `deploy.yml:195`
   and rolls the BFF.
2. **Never `kubectl apply -f 58-*.yaml`.** Confirmed live: nine env vars exist on the running BFF
   that the repo manifest lacks (`DASHBOARD_WRITER_*`, `OPERATOR_*`), and `application.yml:43` binds
   `DASHBOARD_WRITER_PASSWORD` with no default — an apply yields a BFF that **cannot boot**. The two
   new BFF env vars go on by JSON patch (see the operator steps below).
3. **Provision `OPTIONS_CHAT_INGEST_TOKEN`** into `dashboard-secrets` and give the chat-mirror pod
   that token and only that token. It fails closed: while unset, the ingest route rejects everything.

---

## Operator steps that CI will not do for you

1. **Provision two Secret keys BEFORE merging Phase 2**, or the pod crashloops on a missing
   `secretKeyRef` and fails the deploy job. Both are `--type merge` patches that touch no existing
   key (never `create --dry-run | apply`, which replaces the whole `data` map and would wipe
   `AUTH_SECRET` / `BFF_SHARED_TOKEN`):
   - `OPTIONS_CHAT_INGEST_TOKEN` → `dashboard-secrets` (shared by the BFF and the mirror, same
     argument as `BFF_SHARED_TOKEN`). `openssl rand -hex 32`.
   - `DISCORD_OPTIONS_CHAT_CHANNEL_URL` → `sidecar-config` (that Secret already is "which channels
     does the scraper watch").
2. **Wire the BFF with ONE JSON patch, never `kubectl apply -f 58-*.yaml`.** Nine live-only env vars
   are absent from the repo manifest, and `application.yml:43` binds `DASHBOARD_WRITER_PASSWORD` with
   no default — an apply would produce a BFF that **cannot boot**, taking the dashboard read path
   down. Add `OPTIONS_CHAT_ENABLED=true` and the `OPTIONS_CHAT_INGEST_TOKEN` secretKeyRef by patch,
   then verify `DASHBOARD_WRITER_*` / `OPERATOR_*` survived. `DASHBOARD_WRITER_ENABLED=true` is
   already live, so the two-name conditional is satisfied.
3. **Do not `kubectl apply` `40-tenants-config.yaml`** as a side effect of any of this
   (`reference_tenants_configmap_still_needed`).

**No longer needed** (superseded by the verified design): there is no second PVC to seed — the
session is mounted read-only from the existing one. And the first apply of
`62-discord-chat-mirror.yaml` is **not** manual: `discord-chat-mirror` is deliberately kept OUT of
`RESTART_ONLY`, so CI's normal `kubectl apply` creates the Deployment and its NetworkPolicy. Do the
Secret work first, then merge.

---

## RESOLVED 2026-08-13 — the scraper shares the trading Discord account

The operator chose **(b): share the existing account**. A second account was recommended and
declined. That converts the section below from an open question into three binding constraints:

1. **One session is now a single point of failure for the trading feed AND the mirror.** Session
   expiry is no longer a chat-mirror inconvenience — the same `storage_state.json` backs real-money
   signal ingestion. Both session runbooks must cover both pods, and rotation must reach both.
2. **Stagger the two pods' startups** so the sessions never reconnect in lockstep from one IP.
3. **The no-REST-backfill rule is now load-bearing, not stylistic.** It was already forbidden; with a
   shared account, a ban costs the prod signal feed as well. A restart recovers only the ~50–100
   messages Discord has rendered. That gap is accepted — do not "fix" it later.

**Design consequence — mount the session read-only from the existing PVC rather than copying it.**
Two copies of one account's session means a rotation that updates one and not the other leaves a pod
that dies silently at some later reconnect. Mounting `signal-source-discord-state` into the chat pod
with `readOnly: true` keeps one source of truth and leaves the existing cutover runbook working
unchanged; the chat pod writes its heartbeat to its own `emptyDir` instead.

**An earlier draft of this plan called sharing that PVC "fragile" and demanded a second one. That was
wrong**, and it was checked against the live cluster rather than reasoned about: `ReadWriteOnce` is
**node**-scoped, not pod-scoped (`ReadWriteOncePod` is the pod-scoped mode; this PV is plain RWO);
the `local-path` PV carries `nodeAffinity: kubernetes.io/hostname In [homelab]`, so the scheduler
*cannot* split the two pods across nodes even if a node is added — no `podAffinity` pin is needed,
and adding one would be actively harmful (it makes the chat pod unschedulable whenever the signal pod
is scaled to 0, which is step 1 of the session-expiry runbook); `local-path` has no CSI attacher, so
there is no attach/detach controller to raise multi-attach; and empirically **every RollingUpdate of
`signal-source-discord` already runs two pods mounting this PVC read-WRITE simultaneously**, and has
for 89 days. A `readOnly` reader is strictly safer than what ships today.

One real consequence for Phase 6's runbook: Playwright reads `storage_state.json` once, at
`new_context(storage_state=…)` (`main.py:313`), so after a session cutover swaps the file the chat
pod keeps the stale session in memory until restarted. **Add "restart `discord-chat-mirror`" to
`docs/ops/sidecar-session-cutover.md`.**

## Original risk analysis (kept for the reasoning)

**A second concurrent headless session on the irreplaceable Discord account.**
`reference_discord_source_account_irreplaceable` is explicit: a ban means permanent loss of the
prod signal feed, and there is no recovery path. This plan adds a second automated browser session
for that same account from the same IP.

Assessment: low but non-zero. Two concurrent sessions is ordinary for a real user (phone +
desktop), and we are still only *reading* rendered DOM — no REST calls, no gateway self-bot, no
message-history API. That restraint is load-bearing and must survive implementation: **do not add
REST backfill to fill scrollback gaps**, however tempting it is when a restart loses history. The
accepted cost is that a restart recovers only the ~50–100 messages Discord has rendered.

Two ways to close it, operator's call:

- **(a) Use a second Discord account** invited to that guild for the chat mirror, isolating the
  trading account entirely. Strictly safer; needs an invite and one `bootstrap.py` run.
- **(b) Accept the shared account.** Then stagger the two pods' startups so the sessions do not
  reconnect in lockstep, and treat any `storage_state` invalidation as an incident affecting *both*
  services.

I recommend (a) if an invite is obtainable — it converts a catastrophic, unrecoverable failure mode
into an inconvenient one, for roughly ten minutes of setup.

---

## Non-goals

- Posting, reacting, editing, or typing indicators.
- Parsing this channel into trading signals or Temporal workflows.
- Per-tenant filtering (decision #4: one shared feed for all signed-in users).
- Full Discord fidelity (threads, forum posts, stickers, voice, role colors, pinned/pin state).
- Searching or alerting over chat content — deliberately deferred; the schema supports adding it.
