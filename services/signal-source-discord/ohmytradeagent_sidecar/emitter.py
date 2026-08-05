"""Emit a parsed Discord line to the orchestrator as a CopytradeSignalWorkflow start.

Dependency Inversion: the watcher depends on the ``Emitter`` Protocol, not the
concrete Temporal client. Tests inject ``InMemoryEmitter`` (or a mock); production
uses ``TemporalEmitter``. The Protocol stays minimal — a single ``emit`` coroutine —
because that's the only thing the watcher needs (Interface Segregation).

Durable dedupe is Temporal's job. We use ``WorkflowIDReusePolicy.REJECT_DUPLICATE``
so two replicas, or any retry, racing on the same ``signal_id`` produces exactly
one workflow. ``TemporalEmitter.emit`` catches ``WorkflowAlreadyStartedError`` and
returns a typed result rather than letting it propagate — that's what the watcher
needs to log "deduped" and move on.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol

from ohmytradeagent_contract.models.copytrade_derisk_payload import CopytradeDeriskPayload
from ohmytradeagent_contract.models.copytrade_signal_payload import CopytradeSignalPayload
from ohmytradeagent_contract.models.watchlist_mirror_payload import WatchlistMirrorPayload
from ohmytradeagent_contract.search_attributes import TENANT_STRATEGY_KEY
from temporalio.client import Client
from temporalio.common import (
    SearchAttributePair,
    TypedSearchAttributes,
    WorkflowIDReusePolicy,
)
from temporalio.exceptions import WorkflowAlreadyStartedError


WORKFLOW_TYPE = "CopytradeSignalWorkflow"
# Must match the Java workflow interface name exactly.
WATCHLIST_WORKFLOW_TYPE = "WatchlistMirrorWorkflow"
# PLAN-2026-08-04-copytrade-derisk-followup-cue: must match the Java
# CopytradeDeriskWorkflow @WorkflowInterface name exactly.
DERISK_WORKFLOW_TYPE = "CopytradeDeriskWorkflow"


@dataclass(frozen=True)
class EmitResult:
    """Outcome of a single emit call."""

    workflow_id: str
    deduped: bool  # True when Temporal rejected the start as duplicate.


def workflow_id_for(payload: CopytradeSignalPayload) -> str:
    """Deterministic workflow ID from the payload. The shape is locked in
    PLAN.md and acts as the dedupe key alongside REJECT_DUPLICATE.
    """
    return f"t-{payload.tenant_id}/s-{payload.strategy_id}/sig/{payload.signal_id}"


def tenant_strategy_sa(payload: Any) -> str:
    """tenant/strategy search-attribute value. Accepts any payload exposing
    ``tenant_id``/``strategy_id`` (CopytradeSignalPayload or WatchlistMirrorPayload).
    """
    return f"t-{payload.tenant_id}/s-{payload.strategy_id}"


def watchlist_workflow_id_for(payload: WatchlistMirrorPayload) -> str:
    """Deterministic workflow ID for a watchlist mirror. Keyed on
    source_message_id so REJECT_DUPLICATE dedupes re-reads of the same message.
    """
    return f"t-{payload.tenant_id}/s-{payload.strategy_id}/watchlist/{payload.source_message_id}"


def workflow_id_for_derisk(payload: CopytradeDeriskPayload) -> str:
    """Deterministic workflow ID for a de-risk cue. Keyed on the cue's signal_id
    ('<cue_message_id>:derisk') so REJECT_DUPLICATE dedupes re-reads / replicas.
    """
    return f"t-{payload.tenant_id}/s-{payload.strategy_id}/derisk/{payload.signal_id}"


async def _start_workflow_deduped(
    client: Client,
    task_queue: str,
    workflow_type: str,
    wf_id: str,
    sa_value: str,
    payload: Any,
) -> EmitResult:
    """Start a workflow with REJECT_DUPLICATE, mapping an already-started race
    to ``EmitResult(deduped=True)``. Shared by the signal and watchlist emitters.

    The payload is a pydantic model; temporalio serializes it via its default
    DataConverter into JSON whose field names match the contract schema
    (snake_case). The Java side deserializes via Jackson into the generated DTO.
    No bespoke serialization shim — DRY across languages.
    """
    sa = TypedSearchAttributes(
        [SearchAttributePair(TENANT_STRATEGY_KEY, sa_value)]
    )
    payload_dict = payload.model_dump(by_alias=True, mode="json")
    try:
        await client.start_workflow(
            workflow_type,
            payload_dict,
            id=wf_id,
            task_queue=task_queue,
            id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
            search_attributes=sa,
        )
        return EmitResult(workflow_id=wf_id, deduped=False)
    except WorkflowAlreadyStartedError:
        return EmitResult(workflow_id=wf_id, deduped=True)


class Emitter(Protocol):
    """Watcher's only outbound dependency. Single-method Protocol (ISP)."""

    async def emit(self, payload: CopytradeSignalPayload) -> EmitResult: ...

    async def close(self) -> None: ...


class TemporalEmitter:
    """Production emitter: routes parsed signals to the Temporal cluster."""

    def __init__(self, client: Client, task_queue: str) -> None:
        self._client = client
        self._task_queue = task_queue

    @property
    def client(self) -> Client:
        """The connected Temporal client, so a second emitter (e.g. the
        watchlist emitter) can reuse this one connection (no new dial).
        """
        return self._client

    @property
    def task_queue(self) -> str:
        return self._task_queue

    @classmethod
    async def connect(
        cls,
        target: str,
        namespace: str,
        task_queue: str,
    ) -> "TemporalEmitter":
        client = await Client.connect(target, namespace=namespace)
        return cls(client, task_queue)

    async def emit(self, payload: CopytradeSignalPayload) -> EmitResult:
        return await _start_workflow_deduped(
            self._client,
            self._task_queue,
            WORKFLOW_TYPE,
            workflow_id_for(payload),
            tenant_strategy_sa(payload),
            payload,
        )

    async def close(self) -> None:
        # temporalio.Client has no explicit close; the gRPC channel is owned by
        # the underlying service connection and dies with the process. Method
        # kept on the Protocol for symmetry with future emitters that DO need
        # explicit teardown (HTTP gateway, queue producer, etc.).
        return None


class InMemoryEmitter:
    """Test double: records emits and replays dedupe semantics in-process.

    Same Protocol surface so swapping it in for ``TemporalEmitter`` is a
    constructor change in tests, nothing else (Liskov-substitutable).
    """

    def __init__(self) -> None:
        self._seen: set[str] = set()
        self.emitted: list[CopytradeSignalPayload] = []

    async def emit(self, payload: CopytradeSignalPayload) -> EmitResult:
        wf_id = workflow_id_for(payload)
        if wf_id in self._seen:
            return EmitResult(workflow_id=wf_id, deduped=True)
        self._seen.add(wf_id)
        self.emitted.append(payload)
        return EmitResult(workflow_id=wf_id, deduped=False)

    async def close(self) -> None:
        return None


class WatchlistEmitter(Protocol):
    """Watchlist watcher's only outbound dependency. Single-method Protocol (ISP)."""

    async def emit(self, payload: WatchlistMirrorPayload) -> EmitResult: ...

    async def close(self) -> None: ...


class TemporalWatchlistEmitter:
    """Production emitter: starts a WatchlistMirrorWorkflow per daily watchlist.

    Reuses an already-connected ``Client`` and task queue (constructed from
    ``TemporalEmitter.client``) so no second Temporal connection is opened.
    """

    def __init__(self, client: Client, task_queue: str) -> None:
        self._client = client
        self._task_queue = task_queue

    async def emit(self, payload: WatchlistMirrorPayload) -> EmitResult:
        return await _start_workflow_deduped(
            self._client,
            self._task_queue,
            WATCHLIST_WORKFLOW_TYPE,
            watchlist_workflow_id_for(payload),
            tenant_strategy_sa(payload),
            payload,
        )

    async def close(self) -> None:
        # The gRPC channel is owned by the shared TemporalEmitter connection;
        # nothing to tear down here (kept for Protocol symmetry).
        return None


class InMemoryWatchlistEmitter:
    """Test double: records emits and replays dedupe semantics in-process."""

    def __init__(self) -> None:
        self._seen: set[str] = set()
        self.emitted: list[WatchlistMirrorPayload] = []

    def preseed(self, workflow_id: str) -> None:
        """Mark a workflow id as already-started, so a subsequent emit of the
        same id reports ``deduped=True`` — simulates Temporal already holding a
        workflow from before a process restart.
        """
        self._seen.add(workflow_id)

    async def emit(self, payload: WatchlistMirrorPayload) -> EmitResult:
        wf_id = watchlist_workflow_id_for(payload)
        if wf_id in self._seen:
            return EmitResult(workflow_id=wf_id, deduped=True)
        self._seen.add(wf_id)
        self.emitted.append(payload)
        return EmitResult(workflow_id=wf_id, deduped=False)

    async def close(self) -> None:
        return None


class DeriskEmitter(Protocol):
    """De-risk watcher's only outbound dependency. Single-method Protocol (ISP)."""

    async def emit(self, payload: CopytradeDeriskPayload) -> EmitResult: ...

    async def close(self) -> None: ...


class TemporalDeriskEmitter:
    """Production emitter: starts a CopytradeDeriskWorkflow per de-risk cue.

    Reuses an already-connected ``Client`` + task queue (constructed from
    ``TemporalEmitter.client``) so no second Temporal connection is opened —
    same pattern as ``TemporalWatchlistEmitter``.
    """

    def __init__(self, client: Client, task_queue: str) -> None:
        self._client = client
        self._task_queue = task_queue

    async def emit(self, payload: CopytradeDeriskPayload) -> EmitResult:
        return await _start_workflow_deduped(
            self._client,
            self._task_queue,
            DERISK_WORKFLOW_TYPE,
            workflow_id_for_derisk(payload),
            tenant_strategy_sa(payload),
            payload,
        )

    async def close(self) -> None:
        # gRPC channel owned by the shared TemporalEmitter connection.
        return None


class InMemoryDeriskEmitter:
    """Test double: records emits and replays dedupe semantics in-process."""

    def __init__(self) -> None:
        self._seen: set[str] = set()
        self.emitted: list[CopytradeDeriskPayload] = []

    async def emit(self, payload: CopytradeDeriskPayload) -> EmitResult:
        wf_id = workflow_id_for_derisk(payload)
        if wf_id in self._seen:
            return EmitResult(workflow_id=wf_id, deduped=True)
        self._seen.add(wf_id)
        self.emitted.append(payload)
        return EmitResult(workflow_id=wf_id, deduped=False)

    async def close(self) -> None:
        return None
