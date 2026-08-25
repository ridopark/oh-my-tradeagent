#!/usr/bin/env python3
"""Regenerate the #772 discriminating replay fixture.

Takes the recorded pre-#111 copytrade history and injects two keys that are
ABSENT from today's strategy-config.json into the recorded GetStrategyConfig
activity RESULT payload (event 12) — the shape a since-removed schema field
takes in an old history. StrategyConfigLenientReplayTest replays the result:
clean under the wired LenientDataConverter, UnrecognizedPropertyException
under the SDK's strict default.

Asserts the injected keys collide with NO current schema property, so the
fixture stays discriminating as the schema evolves. Rerun after regenerating
the source fixture (see CopytradeSignalWorkflowImplLegacyReplayTest).
"""
import base64
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parent.parent
REPLAY = ROOT / "services/orchestrator/src/test/resources/temporal/replay"
SRC = REPLAY / "copytrade-signal-pre-111-legacy-history.json"
DST = REPLAY / "copytrade-signal-removed-config-field-history.json"
SCHEMA = ROOT / "contract/schemas/strategy-config.json"

INJECT = {"since_removed_field_772": "legacy-value", "since_removed_numeric_knob_772": 42}

history = json.loads(SRC.read_text())
event = history["events"][12]
assert event["eventType"] == "EVENT_TYPE_ACTIVITY_TASK_COMPLETED", event["eventType"]
payload = event["activityTaskCompletedEventAttributes"]["result"]["payloads"][0]
config = json.loads(base64.b64decode(payload["data"]))
assert config.get("strategy_id") == "copytrade-v1", "wrong payload — not the StrategyConfig result"

live_props = set(json.loads(SCHEMA.read_text())["properties"])
collisions = set(INJECT) & live_props
assert not collisions, f"injected keys now exist in the live schema: {collisions}"

config.update(INJECT)
payload["data"] = base64.b64encode(json.dumps(config, separators=(",", ":")).encode()).decode()
DST.write_text(json.dumps(history, indent=2))
print(f"wrote {DST.relative_to(ROOT)} ({len(live_props)} live schema props checked)")
