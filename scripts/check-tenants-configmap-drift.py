#!/usr/bin/env python3
"""Fail if the tenants ConfigMap drifts from the canonical tenant config.

`infra/k8s/40-tenants-config.yaml` embeds verbatim copies of the canonical
`tenants/dev/*` files (k8s ConfigMap keys forbid `/`, so the directory layout is
flattened and reconstructed via the `items:` projection in 51-orchestrator.yaml).
Because it is a hand-maintained copy, it drifts: a key added to the canonical
strategy file is easy to forget here. That exact drift once dropped
`eod_force_flatten: false`, which re-armed the blanket 15:55 ET EOD flatten and
closed a non-0DTE copytrade position (Issue #202).

This is the guard the ConfigMap's own header comment promised. It compares the
*parsed* YAML (so differing comments/whitespace are ignored) of each embedded
block against its canonical source and fails on any semantic difference.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

REPO_ROOT = Path(__file__).resolve().parent.parent
CONFIGMAP = REPO_ROOT / "infra/k8s/40-tenants-config.yaml"

# ConfigMap data key -> canonical source file. Extend when a tenant/strategy is
# added to both the ConfigMap and tenants/.
KEY_TO_CANONICAL = {
    "tenant.yaml": "tenants/dev/tenant.yaml",
    "copytrade-v1.yaml": "tenants/dev/strategies/copytrade-v1.yaml",
    "watchlist-trigger-v1.yaml": "tenants/dev/strategies/watchlist-trigger-v1.yaml",
}


def _flatten(obj, prefix=""):
    """Flatten nested dict/list into {dotted.path: value} for a readable diff."""
    out = {}
    if isinstance(obj, dict):
        for k, v in obj.items():
            out.update(_flatten(v, f"{prefix}.{k}" if prefix else str(k)))
    elif isinstance(obj, list):
        for i, v in enumerate(obj):
            out.update(_flatten(v, f"{prefix}[{i}]"))
    else:
        out[prefix] = obj
    return out


def main() -> int:
    cm = yaml.safe_load(CONFIGMAP.read_text())
    data = cm.get("data", {})
    failures = []

    for key, canonical_rel in KEY_TO_CANONICAL.items():
        if key not in data:
            failures.append(f"ConfigMap data is missing key '{key}'")
            continue
        canonical_path = REPO_ROOT / canonical_rel
        embedded = yaml.safe_load(data[key])
        canonical = yaml.safe_load(canonical_path.read_text())
        if embedded == canonical:
            continue

        emb_flat, can_flat = _flatten(embedded), _flatten(canonical)
        diff = []
        for k in sorted(set(emb_flat) | set(can_flat)):
            if emb_flat.get(k) != can_flat.get(k):
                diff.append(
                    f"    {k}: ConfigMap={emb_flat.get(k)!r} canonical={can_flat.get(k)!r}"
                )
        failures.append(
            f"'{key}' drifted from {canonical_rel}:\n" + "\n".join(diff)
        )

    if failures:
        print(
            "::error::tenants ConfigMap (infra/k8s/40-tenants-config.yaml) drifted "
            "from canonical tenants/dev/*. Re-sync the embedded block(s):",
            file=sys.stderr,
        )
        for f in failures:
            print(f, file=sys.stderr)
        return 1

    print("tenants ConfigMap is in sync with tenants/dev/* ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())
