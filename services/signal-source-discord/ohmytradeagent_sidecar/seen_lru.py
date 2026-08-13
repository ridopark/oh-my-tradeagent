"""Bounded ordered set used as an LRU of already-processed message ids.

Extracted from ``watcher.py`` so the /options-chat mirror can share it WITHOUT importing that
module — ``watcher`` pulls ``.emitter`` → ``temporalio``, tens of MB of Temporal SDK dragged into a
memory-budgeted Chromium pod that never dials Temporal. Re-exported from ``watcher`` under its
original private name so existing call sites and tests are unchanged.
"""

from __future__ import annotations

from collections import OrderedDict


class BoundedSeenLRU:
    """Single-purpose data class: a bounded ordered set used as an LRU cache.

    Extracted as a class so the eviction policy lives in one place rather than
    being inlined in the watcher loop (SRP). The semantics intentionally match
    only the watcher's needs — adding, membership testing, eviction on cap —
    no general-purpose collection API.
    """

    def __init__(self, capacity: int) -> None:
        if capacity <= 0:
            raise ValueError("capacity must be positive")
        self._capacity = capacity
        self._items: OrderedDict[str, None] = OrderedDict()

    def add(self, key: str) -> None:
        if key in self._items:
            self._items.move_to_end(key)
            return
        self._items[key] = None
        if len(self._items) > self._capacity:
            self._items.popitem(last=False)

    def __contains__(self, key: object) -> bool:
        return key in self._items

    def __len__(self) -> int:
        return len(self._items)
