"""Hand-maintained types referenced by the generated pydantic models.

The generated ``models/*.py`` modules used to redeclare ``BrokerTarget`` once
per schema that mentioned it (five identical copies). Adding a new broker
provider then required editing all five files, and a partial edit silently
diverged the contract. Consolidating the enum here, plus a regen-time
post-processor in ``regen.sh``, collapses that to a single edit point.
"""

from .broker_target import BrokerTarget

__all__ = ["BrokerTarget"]
