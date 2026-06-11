# Local development

Two ways to run the system locally, both **isolated from production**.

## Isolated local config — `infra/.env.local`

Local runs use a **separate Alpaca paper account** and **separate Discord read/output channels** so
nothing local ever touches prod's account or posts to prod's channels. These live in a gitignored
`infra/.env.local`:

```sh
cp infra/.env.local.example infra/.env.local
# edit: a dedicated Alpaca paper account, a TEST Discord read channel + a TEST output webhook
```

| Field | Used by | Notes |
|-------|---------|-------|
| `APCA_API_KEY_ID` / `APCA_API_SECRET_KEY` / `APCA_API_BASE_URL` | exec-svc, market-data | a dedicated **local paper** account |
| `DISCORD_CHANNEL_URL` (+ `TENANT_ID`, `STRATEGY_ID`) | signal-source-discord | the channel signals are **read** from — must be a **channel** URL (`https://discord.com/channels/<guild>/<channel>`), **not** a webhook URL (the scraper opens the channel page) |
| `ALERT_DISCORD_WEBHOOK_URL` | orchestrator, exec | the channel outcomes/alerts are **posted** to — this one *is* a webhook URL |
| `BROKER_IMPL=alpaca-paper` | exec-svc | **required for real orders** — the default `stub` is in-memory and places nothing on Alpaca |
| `ALERT_SIGNAL_FEED_ENABLED=true` | orchestrator | post the full signal feed (received + accepted/rejected) to the webhook — default `false` is silent |
| `EXEC_FILL_LISTENER_ENABLED=true` (+ `EXEC_FILL_LISTENER_POLL_ENABLED=true`) | exec-svc | ingest broker fills so a BTO fill opens a PositionWorkflow (Positions/Portfolio populate) |
| `BFF_EXPOSE_BROKER_ACCOUNT_NUMBER=true` | tenant-dashboard-bff | **dev-only** — adds an "Account" column on the Portfolio page showing the broker `account_number`, to confirm which Alpaca account is wired. NEVER set in prod (the broker account is shared across tenants) |

`infra/.env.local` is already gitignored (`*.env.local`) — never commit real values.

## A) Dashboard only (no trading pipeline)

The fast path — see [`dashboard/README.md` § Local development](../../dashboard/README.md). One
command (`make dashboard-dev`) runs Postgres + Temporal + the BFF + the Next.js app with a
passwordless **Dev login**, and `make dashboard-seed` populates sample Trades/Orders/Portfolio data.
This path does **not** use Alpaca or Discord at all.

## B) Full pipeline locally (real, sandboxed data)

Run the whole flow — Discord signal → orchestrator → exec on your **local** Alpaca paper account →
`audit_log` / `order_intent_journal` → dashboard — so the dashboard shows data from a real
(sandboxed) pipeline instead of the static seed.

1. **Fill `infra/.env.local`** (above) — local Alpaca + test Discord channels.

2. **Infra + signal sidecar** (the sidecar is behind the `sidecar` profile and reads the env file):
   ```sh
   # one-time: log into Discord for your TEST channel (interactive — a Chromium window opens via
   # WSLg/X11). Log in; once the channel's messages render it captures the session automatically
   # (auth token included) and exits — no Enter needed.
   docker compose --env-file infra/.env.local -f infra/docker-compose.yml --profile bootstrap \
     run --rm sidecar-bootstrap
   # then bring up the infra (postgres + temporal + redis + ...) and the signal sidecar
   docker compose --env-file infra/.env.local -f infra/docker-compose.yml --profile sidecar up -d
   ```
   > The watcher **seeds** existing channel messages as "already seen" on startup so historical
   > signals never re-fire — to test, post a **new** message *after* it has started.

3. **Run the Java services.** Preferred: `make local-up` runs all three in Docker (built
   from the same Dockerfile CI uses, `restart: unless-stopped` so the stack survives host
   restarts). The `mvn` path below remains useful for debugging a single service in
   isolation (each via `mvn spring-boot:run` **from its module dir** — the
   `spring-boot` plugin prefix only resolves against the module, not the root reactor — sourcing the
   same env so they use the local Alpaca account + output webhook). DB hosts + Temporal default to
   `localhost`; the boot-required bits (`EXEC_DB_URL`, `ORCHESTRATOR_DB_USER/PASS` superuser override,
   `BROKER_IMPL`, `ALERT_SIGNAL_FEED_ENABLED`, `APCA_*`) come from `.env.local`. Give each a distinct
   `SERVER_PORT` (both default Actuator to 8080) and point the orchestrator at the repo's `tenants/`
   with an absolute path (its cwd becomes the module dir):
   ```sh
   set -a; . infra/.env.local; set +a          # export APCA_*, BROKER_IMPL, ALERT_*, ...
   ROOT=$(git rev-parse --show-toplevel)
   ( cd services/orchestrator && ORCHESTRATOR_TENANTS_DIR="$ROOT/tenants" SERVER_PORT=8084 \
       mvn spring-boot:run )   # creates orchestrator.audit_log via Flyway, polls orchestrator-core
   ( cd services/exec         && SERVER_PORT=8085 mvn spring-boot:run )   # EXEC_DB_URL -> exec_alpaca_paper
   ( cd services/market-data  && SERVER_PORT=8086 mvn spring-boot:run )
   ```
   > Heads-up: do **not** also run `make dashboard-seed` against these DBs — the real services' Flyway
   > owns `audit_log` / `order_intent_journal`; the seed's baseline-less tables collide (Flyway then
   > fails with `relation "audit_log" already exists`). The seed is for path (A) only. If you already
   > ran it, drop the seed tables so Flyway can own the schema:
   > ```sh
   > docker exec infra-postgres-1 psql -U temporal -d orchestrator      -c 'DROP TABLE IF EXISTS audit_log CASCADE;'
   > docker exec infra-postgres-1 psql -U temporal -d exec_alpaca_paper -c 'DROP TABLE IF EXISTS order_intent_journal CASCADE;'
   > ```

4. **Run the dashboard** (`make dashboard-dev` brings up its own infra — instead run just the BFF +
   web against the infra already up, or point `DASHBOARD_*`/`BFF_*` at it). The dashboard then shows
   whatever the pipeline produced from your test Discord channel.

Post a signal in your **test** Discord read channel → it flows through to a paper order on your
**local** Alpaca account, and outcomes post to your **test** output webhook — production untouched.

Per-service env beyond the above lives in each service's `src/main/resources/application.yml`.
