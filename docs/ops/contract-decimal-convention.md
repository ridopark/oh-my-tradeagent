# Contract decimal convention (issue #189)

Both languages on the contract treat `"type": "number"` schema fields
that represent money or quantity as arbitrary-precision decimal — not
IEEE-754 double — and the wire shape between them is a bare JSON number
(`3.14`, not `"3.14"`).

## Mapping

- **Java** (`contract/java/`, jsonschema2pojo): `BigDecimal`. Driven by
  `<useBigDecimals>true</useBigDecimals>` in
  `contract/java/pom.xml`. Jackson serialises `BigDecimal` as a bare
  JSON number.
- **Python** (`contract/python/`, `datamodel-codegen`):
  `Annotated[Decimal, Field(gt=0)]` (Pydantic v2), produced by the
  post-processing rewrite in `contract/python/regen.sh`. The same
  post-processor injects `ConfigDict(json_encoders={Decimal: float})`
  into every model so Pydantic's *default* string-shaped Decimal
  serialisation is overridden back to bare-number — matching the Java
  side.

The asymmetry the post-processor fixes is documented in issue #189:
`datamodel-codegen` emits `PositiveFloat` (IEEE-754 double) by default
for `"type": "number", "exclusiveMinimum": 0` fields, which silently
miscomputes the moment any consumer aggregates fills for P&L.

## Wire-shape canary

A regression to string-shaped output (or to `PositiveFloat`) would
break wire compatibility with the Java side. The canary lives in
`contract/python/tests/test_round_trip.py` —
`test_fill_signal_payload_decimal_wire_shape_canary` constructs a
Java-equivalent JSON payload and asserts:

1. Pydantic v2 parses the bare-number form into `Decimal` cleanly.
2. Re-serialising via `model_dump_json(by_alias=True)` produces
   byte-identical output to the input.

Plus `test_decimal_field_accepts_bare_number_and_string_inputs`
locks the parse-side contract: both bare-number and quoted-string JSON
inputs must yield the same `Decimal`, so historical audit records that
were emitted as strings continue to parse cleanly.

## Mechanism (`regen.sh` post-processor)

`contract/python/regen.sh` runs `datamodel-codegen`, then post-processes
the generated `.py` files with two inline Python blocks:

1. `[broker_target dedup]` — collapses the duplicated
   `BrokerTarget(StrEnum)` declarations into a single canonical import.
2. `[positivefloat -> decimal]` (issue #189) — rewrites
   `PositiveFloat` → `Annotated[Decimal, Field(gt=0)]` and injects
   `json_encoders={Decimal: float}` into every model's `ConfigDict`.

Both rewrites are idempotent — re-running `regen.sh` against already-
rewritten files is a no-op. The CI regen-drift gate
(`regen.sh` then `git diff --quiet contract/python/`) verifies this.

## `json_encoders` and Pydantic v3

`json_encoders` is the documented Pydantic v2 escape hatch for
overriding the default `Decimal` JSON serialisation. It is deprecated
"in v3" (no concrete release date as of this writing) and emits a
`PydanticDeprecatedSince20` warning at model-class definition time. The
warning is suppressed at the runner level via the test config; the
wire-shape canary plus the convention sweep keep the contract
guarantees regardless of the underlying mechanism. When v3 ships we
will migrate the post-processor to inject the equivalent
`PlainSerializer(lambda v: v, when_used="json")` annotation per field
(or use whatever the v3 documentation recommends at that time).

## Out of scope: `confloat(...)` ratios

A handful of fields use `confloat(le=1.0, gt=0.0)` for ratios (giveback
percentages, capital weight, fraction-of-position partial exits).
These remain as `float` because:

1. They are pure ratios, not money — IEEE-754 precision is sufficient.
2. They are bounded `(0, 1]`, so accumulated drift cannot wander into
   absurd values the way drift on a 5-digit dollar amount can.
3. The cost of churning every consumer to accept `Decimal` in place of
   `float` isn't worth the precision benefit for these specific fields.

If a future consumer ever does precision-sensitive arithmetic on a
ratio, file a follow-up issue and move that field into the `Decimal`
sweep.
