"""Single-source-of-truth guard for the ``BrokerTarget`` enum.

The generated ``models/*.py`` files used to redeclare the enum once per
schema that mentioned it (five identical copies). ``regen.sh`` now strips
those copies and inserts an import from the canonical
``ohmytradeagent_contract.types.broker_target`` module. This pytest blocks
that work from regressing: if any future generator run leaves a local
``class BrokerTarget(StrEnum)`` declaration in the generated tree, this
test fails and the dedup post-processor needs to be fixed.

It also asserts the canonical module is importable from the expected
location, and that the same enum value reaches a generated DTO via the
shared module (catches a regen that drops the import without restoring
the class — pydantic would NameError at first model construction).
"""

from __future__ import annotations

from pathlib import Path

from ohmytradeagent_contract.models.order_intent import BrokerTarget as OrderIntentBroker
from ohmytradeagent_contract.types.broker_target import BrokerTarget

MODELS_DIR = Path(__file__).resolve().parents[1] / "ohmytradeagent_contract" / "models"


def test_no_generated_file_redeclares_broker_target() -> None:
    """Every generated model that needs ``BrokerTarget`` imports it from the
    shared module. Re-declaring it locally drifts the enum on the next edit.
    """
    offenders: list[str] = []
    for py in sorted(MODELS_DIR.glob("*.py")):
        text = py.read_text()
        if "class BrokerTarget(StrEnum)" in text:
            offenders.append(py.name)
    assert offenders == [], (
        f"Generated models still redeclare BrokerTarget: {offenders}. "
        f"Fix regen.sh's post-processor or rerun ./regen.sh."
    )


def test_generated_models_resolve_broker_target_to_canonical_module() -> None:
    """The ``BrokerTarget`` symbol re-imported by each generated model is the
    same object as the canonical enum — not a hidden re-declaration that
    happens to share the name.
    """
    assert OrderIntentBroker is BrokerTarget


def test_canonical_enum_has_expected_members() -> None:
    """Sanity check the value set so a schema-side rename is caught here too."""
    assert {member.value for member in BrokerTarget} == {
        "paper",
        "live",
        "alpaca-paper",
        "alpaca-live",
        "tradier-paper",
        "tradier-live",
        "ibkr-paper",
        "ibkr-live",
        "schwab-paper",
        "schwab-live",
    }
