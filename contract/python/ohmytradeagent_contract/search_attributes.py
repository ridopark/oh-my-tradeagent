"""Canonical ``SearchAttributeKey`` constants shared across Python callers.

Both the Discord sidecar emitter (``services/signal-source-discord``) and the
harness scripts (``scripts/harness/inject_synthetic_*.py``) need to start
workflows tagged with the two custom Search Attributes registered by
``infra/k8s/31-temporal-bootstrap.yaml``:

* ``TenantStrategy`` — used by visibility queries to fan kill-switch signals
  out to every workflow under a (tenant, strategy), and by the API gateway to
  list positions per tenant.
* ``ContractSymbol`` — used by the position lookup activity to route STC
  dispatch via ``TenantStrategy + ContractSymbol`` filters.

Before this module landed each caller redeclared
``SearchAttributeKey.for_keyword("TenantStrategy")`` inline, so a rename or a
case-typo in any one place would silently route workflows to an empty
visibility query. Consolidating here keeps the Python side a single edit
point. The matching Java constants live in
``contract/java/src/main/java/com/ohmytradeagent/contract/identity/WorkflowIds.java``
and are intentionally not consumed by this module — the cross-language shared
source is the bootstrap manifest, not a code symbol.

This module is hand-maintained (it does NOT live under ``models/`` because
``regen.sh`` only regenerates pydantic models from JSON schemas; the
SearchAttributeKey constants have no schema counterpart).
"""

from __future__ import annotations

from temporalio.common import SearchAttributeKey


TENANT_STRATEGY_KEY: SearchAttributeKey[str] = SearchAttributeKey.for_keyword("TenantStrategy")
"""Keyword Search Attribute used by every copy-trade workflow to declare its
``(tenant, strategy)`` identity. Value shape: ``t-<tenant>/s-<strategy>``."""

CONTRACT_SYMBOL_KEY: SearchAttributeKey[str] = SearchAttributeKey.for_keyword("ContractSymbol")
"""Keyword Search Attribute used by ``PositionWorkflow`` to declare its OCC
option symbol so STC dispatch can find it via a visibility query."""


__all__ = ["TENANT_STRATEGY_KEY", "CONTRACT_SYMBOL_KEY"]
