"""Single-source-of-truth guard for the Python ``SearchAttributeKey`` constants.

The sidecar emitter and the harness inject scripts used to redeclare the
``TenantStrategy`` and ``ContractSymbol`` keyword Search Attributes inline.
A case-typo or rename in any one of those three places would silently
route workflows to an empty visibility query. Consolidating them in
``ohmytradeagent_contract.search_attributes`` made the Python side a single
edit point; this pytest blocks the consolidation from regressing.
"""

from __future__ import annotations

from pathlib import Path

from temporalio.common import SearchAttributeKey

from ohmytradeagent_contract.search_attributes import (
    CONTRACT_SYMBOL_KEY,
    TENANT_STRATEGY_KEY,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
CANONICAL_MODULE = (
    REPO_ROOT / "contract" / "python" / "ohmytradeagent_contract" / "search_attributes.py"
)

# Trees that the consolidation covers. Java has its own constants (see
# ``contract/java/.../identity/WorkflowIds.java``) and is intentionally out of
# scope — the cross-language source-of-truth is the bootstrap manifest, not a
# code symbol.
SCAN_ROOTS = (
    REPO_ROOT / "contract" / "python",
    REPO_ROOT / "services" / "signal-source-discord",
    REPO_ROOT / "scripts" / "harness",
)

# Directories that are not first-party source. The scan roots are walked
# recursively, and ``services/signal-source-discord`` carries a local ``.venv``
# with this very package installed into it — so the canonical module reappears
# under a second path and reads as an offender, failing the guard on any
# developer machine that has run ``uv sync`` (CI, with no venv on disk, passes).
# The identity check below cannot catch it: it is the same file, at a different
# resolved path. Vendored/installed trees are out of scope by definition — this
# guard polices what we write, not what we install.
EXCLUDED_DIR_NAMES = frozenset(
    {
        ".venv",
        "venv",
        "site-packages",
        "node_modules",
        "__pycache__",
        ".tox",
        ".mypy_cache",
        "build",
        "dist",
    }
)

# The literals are assembled from parts so this test file itself doesn't match
# the regression scan and become a false-positive offender.
_FOR_KEYWORD = "SearchAttributeKey" + "." + "for_keyword"
LITERAL_TENANT = f'{_FOR_KEYWORD}("TenantStrategy")'
LITERAL_CONTRACT = f'{_FOR_KEYWORD}("ContractSymbol")'


def test_constants_are_keyword_search_attribute_keys() -> None:
    """The exported constants must be ``SearchAttributeKey`` instances with the
    exact server-registered names. A wrong type or a typo here would break
    every workflow-start call in the sidecar + harness.
    """
    assert isinstance(TENANT_STRATEGY_KEY, SearchAttributeKey)
    assert isinstance(CONTRACT_SYMBOL_KEY, SearchAttributeKey)
    assert TENANT_STRATEGY_KEY.name == "TenantStrategy"
    assert CONTRACT_SYMBOL_KEY.name == "ContractSymbol"


def _scan_for_literal(literal: str) -> list[str]:
    """Return the relative-to-repo-root paths that contain ``literal``."""
    hits: list[str] = []
    canonical = CANONICAL_MODULE.resolve()
    for root in SCAN_ROOTS:
        for py in root.rglob("*.py"):
            if EXCLUDED_DIR_NAMES.intersection(py.parts):
                continue
            # Skip the canonical module itself — it is allowed (and required)
            # to construct the keys.
            if py.resolve() == canonical:
                continue
            if literal in py.read_text():
                hits.append(str(py.relative_to(REPO_ROOT)))
    return sorted(hits)


def test_no_caller_redeclares_tenant_strategy_key() -> None:
    """Only ``search_attributes.py`` may construct the TenantStrategy key."""
    offenders = _scan_for_literal(LITERAL_TENANT)
    assert offenders == [], (
        f"Files still redeclare TenantStrategy SearchAttributeKey: {offenders}. "
        f"Import TENANT_STRATEGY_KEY from "
        f"ohmytradeagent_contract.search_attributes instead."
    )


def test_no_caller_redeclares_contract_symbol_key() -> None:
    """Only ``search_attributes.py`` may construct the ContractSymbol key."""
    offenders = _scan_for_literal(LITERAL_CONTRACT)
    assert offenders == [], (
        f"Files still redeclare ContractSymbol SearchAttributeKey: {offenders}. "
        f"Import CONTRACT_SYMBOL_KEY from "
        f"ohmytradeagent_contract.search_attributes instead."
    )
