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
from typing import Protocol

from ohmytradeagent_contract.models.copytrade_signal_payload import CopytradeSignalPayload
from temporalio.client import Client
from temporalio.common import (
    SearchAttributeKey,
    SearchAttributePair,
    TypedSearchAttributes,
    WorkflowIDReusePolicy,
)
from temporalio.exceptions import WorkflowAlreadyStartedError
from temporalio.service import RPCError, RPCStatusCode


WORKFLOW_TYPE = "CopytradeSignalWorkflow"


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


def tenant_strategy_sa(payload: CopytradeSignalPayload) -> str:
    return f"t-{payload.tenant_id}/s-{payload.strategy_id}"


class Emitter(Protocol):
    """Watcher's only outbound dependency. Single-method Protocol (ISP)."""

    async def emit(self, payload: CopytradeSignalPayload) -> EmitResult: ...

    async def close(self) -> None: ...


class TemporalEmitter:
    """Production emitter: routes parsed signals to the Temporal cluster."""

    _TENANT_STRATEGY_KEY = SearchAttributeKey.for_keyword("TenantStrategy")

    def __init__(self, client: Client, task_queue: str) -> None:
        self._client = client
        self._task_queue = task_queue

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
        wf_id = workflow_id_for(payload)
        sa = TypedSearchAttributes(
            [SearchAttributePair(self._TENANT_STRATEGY_KEY, tenant_strategy_sa(payload))]
        )
        # The payload is a pydantic model; temporalio serializes it via its
        # default DataConverter into JSON whose field names match the contract
        # schema (snake_case). The Java side deserializes via Jackson into the
        # generated DTO. No bespoke serialization shim — DRY across languages.
        payload_dict = payload.model_dump(by_alias=True, mode="json")
        try:
            await self._client.start_workflow(
                WORKFLOW_TYPE,
                payload_dict,
                id=wf_id,
                task_queue=self._task_queue,
                id_reuse_policy=WorkflowIDReusePolicy.REJECT_DUPLICATE,
                search_attributes=sa,
            )
            return EmitResult(workflow_id=wf_id, deduped=False)
        except WorkflowAlreadyStartedError:
            return EmitResult(workflow_id=wf_id, deduped=True)
        except RPCError as exc:
            if exc.status == RPCStatusCode.ALREADY_EXISTS:
                return EmitResult(workflow_id=wf_id, deduped=True)
            raise

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
