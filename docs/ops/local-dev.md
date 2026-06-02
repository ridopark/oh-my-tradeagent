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
| `DISCORD_CHANNEL_URL` (+ `TENANT_ID`, `STRATEGY_ID`) | signal-source-discord | the channel signals are **read** from |
| `ALERT_DISCORD_WEBHOOK_URL` | orchestrator, exec | the channel outcomes/alerts are **posted** to |

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
   # one-time: capture Discord cookies for your TEST channel (interactive)
   docker compose --env-file infra/.env.local -f infra/docker-compose.yml --profile bootstrap \
     run --rm sidecar-bootstrap
   # then bring up postgres + temporal + the signal sidecar
   docker compose --env-file infra/.env.local -f infra/docker-compose.yml --profile sidecar up -d
   ```

3. **Run the Java services** (each via `mvn`, sourcing the same env so they use the local Alpaca
   account + output webhook). The DB/Temporal defaults already point at `localhost`:
   ```sh
   set -a; . infra/.env.local; set +a          # export APCA_*, ALERT_DISCORD_WEBHOOK_URL, ...
   mvn -pl services/orchestrator   -am spring-boot:run    # creates orchestrator.audit_log via Flyway
   mvn -pl services/exec           -am spring-boot:run    # EXEC_DB_URL -> exec_alpaca_paper
   mvn -pl services/market-data    -am spring-boot:run
   ```
   > Heads-up: do **not** also run `make dashboard-seed` against this DB — the real services' Flyway
   > owns `audit_log` / `order_intent_journal`; the seed's baseline-less tables would collide. The
   > seed is for path (A) only.

4. **Run the dashboard** (`make dashboard-dev` brings up its own infra — instead run just the BFF +
   web against the infra already up, or point `DASHBOARD_*`/`BFF_*` at it). The dashboard then shows
   whatever the pipeline produced from your test Discord channel.

Post a signal in your **test** Discord read channel → it flows through to a paper order on your
**local** Alpaca account, and outcomes post to your **test** output webhook — production untouched.

Per-service env beyond the above lives in each service's `src/main/resources/application.yml`.
