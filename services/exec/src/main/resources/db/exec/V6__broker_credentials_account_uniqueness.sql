-- R-6.5 per-broker-target account uniqueness. Uniqueness is keyed on the
-- account being NON-BLANK (a real brokerage account), NOT on the pod being
-- live: a real account must bind to at most ONE tenant, else two tenants
-- sharing an expected_account_id would double-size the same account. A PARTIAL
-- unique index constrains only non-blank rows, so multiple rows with a
-- blank/NULL expected_account_id still coexist (unconstrained). On the
-- exec-alpaca-live DB this IS the live double-bind guard; it also holds on the
-- paper DB (where tenants have distinct accounts today). This is the
-- race-proof, fail-closed authority; the BrokerCredentialWriter pre-persist
-- check (and, under a concurrent race, the UPSERT's index-violation
-- translation) turns the same collision into a clean 409.
--
-- The predicate uses a POSIX match on a non-whitespace character
-- (~ '[^[:space:]]') so a row is constrained IFF expected_account_id contains
-- at least one non-whitespace char — matching Java String.isBlank() EXACTLY
-- (which the writer + read-path live seals use). A whitespace-only value
-- (space, tab, newline) is therefore unconstrained in BOTH layers, keeping the
-- SQL and Java notions of "blank" consistent. (~ with a constant pattern is
-- IMMUTABLE, so it is valid in a partial-index predicate; a NULL account yields
-- NULL and is excluded.)
CREATE UNIQUE INDEX broker_credentials_provider_account_uk
  ON broker_credentials (provider, expected_account_id)
  WHERE expected_account_id ~ '[^[:space:]]';
