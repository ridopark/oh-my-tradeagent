# Plan — Readable watchlist embed + tolerate trailing chatter + never ping on a mirrored mention

## Context

Today's real watchlist ended with **"Good luck @everyone"**. In the live mirror that causes two problems:

1. **The embed silently degrades to raw text.** `WatchlistParser` marks a watchlist `clean=false` if **any** non-blank line isn't a level line (other than the author header). The trailing greeting trips that, so `WatchlistMirrorActivitiesImpl.postWatchlistAlert` takes the **raw-text fallback** instead of rendering the parsed embed table. (Verified earlier: this is why the manual remediation injected only the level-lines.)
2. **A mirrored mention can ping the whole channel.** The Discord webhook posts `{"content": …}` / `{"embeds": […]}` with **no `allowed_mentions`** restriction. In the raw-fallback path the content carries the literal `@everyone`, so the alert-channel post would **notify everyone** (and the same exposure exists for any alert whose text ever contains `@everyone`/`@here`/a role/user mention). (Mentions inside an embed *description* don't ping — but the raw fallback is plain content, which does.)

## The fix (two parts)

### A. Parser tolerates marker-less "chatter" lines (so the embed still renders)
In `WatchlistParser` (`services/orchestrator/.../activities/WatchlistParser.java`): a non-level line should **not** flip `clean=false` when it is clearly not a (possibly-malformed) level — i.e. it contains **none** of the level-grammar markers. Add an `isIgnorableChatter(line)` predicate: a trimmed non-blank line is ignorable iff it is the author/time header **or** it contains neither a `>`/`<` comparator **nor** a `<number><c|p>` strike+right token. "Good luck @everyone", greetings, emoji, blank lines → ignorable. A line that *has* a comparator or a strike+right token but doesn't fully parse → **still** flips `clean=false` (preserve the raw fallback for a genuinely malformed level — never silently drop a real level). `clean` stays true iff every non-blank line is a valid level, the header, or ignorable chatter, AND there is ≥1 parsed row.

This keeps the deliberate "never silently lose a level" guarantee while no longer punishing a friendly sign-off.

### B. Webhook never pings — `allowed_mentions:{parse:[]}` on every post (defense-in-depth)
In `DiscordWebhookClient` (BOTH `services/orchestrator/.../alert/DiscordWebhookClient.java` and `services/exec/.../alert/DiscordWebhookClient.java`): add `"allowed_mentions":{"parse":[]}` to the JSON body of **both** the `post(content)` path and the `postEmbed(...)` path (in the shared `send`/body-builder). With `parse:[]`, Discord renders `@everyone`/`@here`/`<@…>`/`<@&…>` as text but **suppresses all notifications**. These are operator alert feeds — none of them should ever ping — so this is a safe, broad safety net that also covers the raw-fallback watchlist case and any future alert text.

### C. Readable, wider embed — per-play lines, no code block (locked with user)
Operator feedback: the current code-block table embed is cramped and hard to read, and the code block forces a narrow box. Replace the table renderer in `WatchlistMirrorActivitiesImpl` (the `renderTable` method that builds the embed **description**) with a **per-play** renderer:
- **One line per leg**, no ``` code fence (so the embed description uses the embed's full width):
  - call leg → `📈 **<TICKER> <strike>C** — breaks above <trigger>`
  - put leg → `📉 **<TICKER> <strike>P** — breaks below <trigger>`
- Per ticker, emit the call line (if present) then the put line (if present); tickers in original order. A ticker with only one side emits only that line (no `—` placeholder needed in this layout).
- Keep the embed shell unchanged: title `📋 Watchlist — <MMM d, yyyy>`, Discord green, footer `via <author>`.
- Bold the ticker+strike (the "play"); the trigger stays plain. Strike printed without trailing `.0`; trigger 2-decimal (reuse the existing `formatLeg` number formatting).
- Truncation: the embed description cap stays 4096 (existing); truncate with the existing note if somehow exceeded.
- The **raw-text fallback** (`format()`, used when the parse is not clean) is unchanged — it stays a fenced raw block.

Chosen over a plain (non-embed) full-channel-width message and over a code-block table: keeps the colored card, drops the width-constraining code fence, and the per-play lines are self-contained so no monospace alignment is needed.

### Constraints
- Part A: pure parser change; the raw fallback path is unchanged. Keep the existing author-header detection.
- Part C: only the embed **description** content changes (table → per-play, no fence); the embed title/color/footer, the parse→clean gate, and the raw fallback are unchanged. `WatchlistParser`/`TickerWatch` (the structured rows) are unchanged — this is pure presentation.
- Part B: add the key to the existing hand-built JSON via the existing `jsonString` escaper / body construction — `allowed_mentions` is a fixed `{"parse":[]}` literal (no user input), appended alongside `content`/`embeds`. Do NOT switch to Jackson. Behavior otherwise identical (no-op on blank URL, never-throw).
- No contract change; no new audit kind; `services/exec` gets the same Part B change for parity (its broker-rejection alert posts the same way).

## Tests (TDD)

Part A — `WatchlistParserTest` (orchestrator):
1. `parse_watchlistWithTrailingGreeting_isCleanAndIgnoresChatter`: the real sample (SPY/QQQ/… + a trailing `"Good luck @everyone"` and blank lines) → `clean == true`, rows == the tickers, the greeting is not a row.
2. `parse_malformedLevelLine_staysUncleanForRawFallback`: a line with a comparator but broken format (e.g. `"SPY 762x > 761"` or `"SPY > 761"`) → `clean == false` (so the raw fallback still fires — a real level is never silently dropped).
3. Keep green: the existing clean-parse and author-header tests.

Part C — `WatchlistMirrorActivitiesImplTest` (orchestrator): the embed render is now per-play lines (no code fence). For the real sample, assert the embed **description**:
- contains `📈 **SPY 756C** — breaks above 755.30` and `📉 **SPY 745P** — breaks below 748.00` (both legs, correct emoji/direction/trigger);
- a put-only ticker (MSFT) → only `📉 **MSFT 420P** — breaks below 424.00`, no call line / no `—`;
- contains **no** ```` ``` ```` code fence in the description (it's plain embed text now);
- title contains the date, footer contains the author, color is the green int.
And: a payload whose `raw_text` ends with `"Good luck @everyone"` → `postEmbed` is called (Part A: not the raw `post` fallback).

Part B — `DiscordWebhookClientTest` (orchestrator AND exec):
4. `post(content)` body contains `"allowed_mentions":{"parse":[]}` and the existing `content`.
5. `postEmbed(...)` body contains `"allowed_mentions":{"parse":[]}` and the `embeds` array.
6. A `content`/embed field containing `@everyone` still posts (escaped) — assert the body has `allowed_mentions:{parse:[]}` so it can't ping. Keep the existing blank-URL no-op + escaping tests green.

## Success criteria (must all hold)
1. `mvn -B -ntp -pl services/orchestrator,services/exec -am test` → BUILD SUCCESS, 0 failures (KillSwitchWorkflowImplTest known-flaky: re-run once).
2. A watchlist with trailing chatter renders the **embed** (Part A: `clean=true` → `postEmbed`), verified by tests 1 + the activity test.
3. A genuinely malformed level line still triggers the **raw fallback** (`clean=false`), verified by test 2 — the never-drop-a-level guarantee is preserved.
4. Every webhook post (content + embed, orchestrator + exec) includes `"allowed_mentions":{"parse":[]}`, verified by tests 4-6 — a mirrored `@everyone`/`@here`/role/user mention cannot notify the channel.
5. The watchlist embed renders as **per-play lines with no code fence** (Part C), verified by the activity test (both-legs, put-only, no-fence, title/footer/color assertions).
6. `mvn -B -ntp -pl services/orchestrator,services/exec spotless:apply` then `spotless:check` clean.

## Halt conditions
- If suppressing mentions on the webhook would break an alert that *intentionally* pings (audit the alerters — none should) → stop and surface.
- If the chatter heuristic can't distinguish a friendly sign-off from a malformed level without risking dropping a real level → stop; keep the stricter raw-fallback (correctness over prettiness).

## Verification commands
```
mvn -B -ntp -pl services/orchestrator,services/exec -am -Dtest=WatchlistParserTest,WatchlistMirrorActivitiesImplTest,DiscordWebhookClientTest test
mvn -B -ntp -pl services/orchestrator,services/exec -am test
mvn -B -ntp -pl services/orchestrator,services/exec spotless:apply && mvn -B -ntp -pl services/orchestrator,services/exec spotless:check
```

## Out of scope
- The watchlist midnight race / posted-date gate (already fixed, #359) and the exit/STC fixes (#357/#358).
- Any change to which alerts fire or their routing; only their rendering (embed vs raw) and mention-suppression change.
