"""MCP server factory — registers all tools and the prompt.

`build_server(scope, *, today=None)` is the seam between startup and the
tool layer.  The *scope* callable is called on every tool invocation to
resolve `(TenantBinding, BffClient)` for that request:

    async def scope(tenant: str | None) -> tuple[TenantBinding, BffClient]: ...

For backward compatibility, `build_server(binding, bff, *, today=None)` is
still accepted; it wraps the positional args in `make_constant_scope`.

`make_constant_scope(binding, bff, *, strict=False)` builds a constant-scope
callable suitable for the local stdio mode:
- strict=False (default): ignores the tenant arg (backward compat for existing tests)
- strict=True: raises ToolError when the tenant arg mismatches the bound tenant

Step 2 ships `get_portfolio_summary`.
Step 3 adds `list_open_positions`, `get_position_history`, `get_recent_closed_trades`.
Step 4 adds `get_quote`, `get_chain`, `get_underlying_context` and the `analyze_positions` prompt.
Step 3a adds per-call `scope(tenant)` resolution and audit logging for HTTP mode.
"""
from __future__ import annotations

import hashlib
import json
import logging
import sys
import zoneinfo
from collections.abc import Awaitable, Callable
from datetime import date
from typing import Any, NoReturn

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from ohmytradeagent_mcp.bff import BffClient, BffError
from ohmytradeagent_mcp.occ import dte as _dte_days
from ohmytradeagent_mcp.resolver import TenantBinding
from ohmytradeagent_mcp.sanitize import UNTRUSTED_NOTE, has_untrusted, sanitize

log = logging.getLogger(__name__)

_MARKET_TZ = zoneinfo.ZoneInfo("America/New_York")

# Type alias for the scope callable.
ScopeCallable = Callable[[str | None], Awaitable[tuple[TenantBinding, BffClient]]]


def make_constant_scope(
    binding: TenantBinding,
    bff: BffClient,
    *,
    strict: bool = False,
) -> ScopeCallable:
    """Return a scope callable that always returns the same (binding, bff).

    Args:
        binding: The pre-resolved tenant binding.
        bff: The pre-configured BFF client.
        strict: When True, raise ToolError if the tenant arg is not None and
            does not match binding.tenant_id.  When False (the default),
            the tenant arg is silently ignored.
    """

    async def _scope(tenant: str | None) -> tuple[TenantBinding, BffClient]:
        if strict and tenant is not None and tenant != binding.tenant_id:
            raise ToolError(
                f"not authorised for tenant {tenant!r}: "
                f"this session is bound to {binding.tenant_id!r}"
            )
        return binding, bff

    return _scope


def _do_audit(binding: TenantBinding, tool: str, outcome: str) -> None:
    """Write an audit line to stderr in HTTP mode; no-op in stdio mode.

    HTTP mode is detected by the presence of the auth contextvar
    (get_access_token() returns non-None only inside an authenticated request).
    """
    try:
        from mcp.server.auth.middleware.auth_context import get_access_token
        at = get_access_token()
        if at is None:
            return
        sub = at.subject or ""
        sub_hash = hashlib.sha256(sub.encode()).hexdigest()[:12]
        cid = binding.client_id or "unknown"
        print(
            f"mcp tool subject_hash={sub_hash} client_id={cid} "
            f"tenant={binding.tenant_id} tool={tool} outcome={outcome}",
            file=sys.stderr,
        )
    except Exception:  # noqa: BLE001, S110
        pass  # audit failure must never break the tool


def build_server(
    scope_or_binding: ScopeCallable | TenantBinding,
    bff: BffClient | None = None,
    *,
    today: date | None = None,
    mcp: MCPServer | None = None,
) -> MCPServer:
    """Return a configured MCPServer with all tools and the prompt registered.

    Signatures:
        build_server(scope)                      — new API (HTTP gateway mode)
        build_server(scope, today=…)             — new API with pinned date
        build_server(binding, bff)               — old API (backward compat)
        build_server(binding, bff, today=…)      — old API with pinned date

    Args:
        scope_or_binding: Either a scope callable `async (tenant) → (binding, bff)`,
            or a `TenantBinding` (old positional API; also pass *bff* then).
        bff: BffClient when using the old positional API; unused in new API.
        today: Pins the reference date used for DTE calculations.  When None the
            server uses the current date in America/New_York (injected in tests
            via this parameter so the DTE assertions are deterministic).
        mcp: Optional pre-built MCPServer to register tools on.  When None a
            new MCPServer is created.  Pass a server with auth=AuthSettings(…)
            and token_verifier=… for the HTTP gateway mode.
    """
    if isinstance(scope_or_binding, TenantBinding):
        # Backward compat: wrap (binding, bff) as a constant scope
        scope: ScopeCallable = make_constant_scope(scope_or_binding, bff)  # type: ignore[arg-type]
    else:
        scope = scope_or_binding

    if mcp is None:
        mcp = MCPServer(
            name="omt-mcp",
            description=(
                "Read-only operator view of one oh-my-tradeagent tenant's positions, "
                "risk state and market context.  Cannot trade."
            ),
        )

    _register_tools(mcp, scope, today)
    _register_prompts(mcp)
    return mcp


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------


def _tool_result(data: Any, binding: TenantBinding) -> dict:
    """Wrap *data* as a sanitised tool result, adding tenant_id and an
    untrusted-text note when the sanitiser wrapped any author field."""
    sanitised = sanitize(data)
    result: dict[str, Any] = {"tenant_id": binding.tenant_id}
    if isinstance(sanitised, dict):
        result.update(sanitised)
    else:
        result["data"] = sanitised
    if has_untrusted(sanitised):
        result["untrusted_text_note"] = UNTRUSTED_NOTE
    return result


def _today_ny(today_param: date | None) -> date:
    """Return the reference date: *today_param* when given, else today in NY."""
    if today_param is not None:
        return today_param
    from datetime import datetime

    return datetime.now(_MARKET_TZ).date()


def _raise_manual_entry_error(exc: BffError, context: str) -> NoReturn:
    """Map a BffError from a manual-entry route into a ToolError.

    Raises ToolError for 404 manual_entry_disabled; otherwise raises ToolError
    with detail or status/error_code as available.  Never returns.
    """
    body = exc.body if isinstance(exc.body, dict) else {}
    error_code = body.get("error", "")
    if exc.status == 404 and error_code == "manual_entry_disabled":
        raise ToolError(
            f"{context} unavailable: manual entry is disabled for this tenant "
            "(BFF 404 manual_entry_disabled); no other quote route is exposed"
        ) from exc
    detail = body.get("detail", "")
    if detail:
        raise ToolError(f"{context} unavailable: BFF {exc.status} {error_code} — {detail}") from exc
    raise ToolError(f"{context} unavailable: BFF {exc.status} {error_code}") from exc


def _register_tools(
    mcp: MCPServer,
    scope: ScopeCallable,
    today: date | None,
) -> None:
    # ------------------------------------------------------------------
    # get_portfolio_summary
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_portfolio_summary(tenant: str | None = None) -> dict:
        """Return a summary of the tenant's portfolio: equity, cash, open exposure,
        notional-cap headroom (from the same formula the orchestrator's risk gate uses),
        realized P&L, and kill-switch state.

        Note: notional-cap figures are informational — the positions reader is
        best-effort and may undercount open notional if a position query races,
        slightly overstating headroom.  Kill-switch is null when no kill-switch
        workflow has been bootstrapped for this tenant.

        Args:
            tenant: Tenant identifier (required when this token covers multiple tenants).
        """
        binding, bff = await scope(tenant)

        # --- portfolio ---
        try:
            portfolio = await bff.get("/api/portfolio")
        except BffError as exc:
            _do_audit(binding, "get_portfolio_summary", "error")
            raise ToolError(f"portfolio unavailable: BFF {exc.status} from /api/portfolio") from exc

        # --- kill switch (404 → null + note, any other error propagates) ---
        kill_switch: Any = None
        kill_switch_note: str | None = None
        try:
            kill_switch = await bff.get("/api/account-killswitch")
        except BffError as exc:
            if exc.status == 404:
                kill_switch = None
                kill_switch_note = "no kill-switch workflow bootstrapped for this tenant (BFF 404)"
            else:
                _do_audit(binding, "get_portfolio_summary", "error")
                raise ToolError(
                    f"kill-switch unavailable: BFF {exc.status} from /api/account-killswitch"
                ) from exc

        # --- notional-cap headroom ---
        try:
            headroom = await bff.get("/api/notional-cap")
        except BffError as exc:
            _do_audit(binding, "get_portfolio_summary", "error")
            raise ToolError(
                f"notional-cap unavailable: BFF {exc.status} from /api/notional-cap"
            ) from exc

        data: dict[str, Any] = {}
        for key in (
            "trading_day",
            "open_positions_count",
            "open_positions_degraded",
            "sum_open_notional",
            "sum_open_notional_basis",
            "realized_pnl_today",
            "realized_pnl_all_time",
            "account_equity",
        ):
            if key in portfolio:
                data[key] = portfolio[key]

        data["kill_switch"] = kill_switch
        if kill_switch_note:
            data["kill_switch_note"] = kill_switch_note
        data["headroom"] = headroom

        result = _tool_result(data, binding)
        _do_audit(binding, "get_portfolio_summary", "ok")
        return result

    # ------------------------------------------------------------------
    # list_open_positions
    # ------------------------------------------------------------------
    @mcp.tool()
    async def list_open_positions(tenant: str | None = None) -> dict:
        """Return open positions enriched with floor-breach status, live market
        marks, and the author's entry signal.

        Note: open_notional is cost-basis-at-entry (entry_premium × remaining_qty × 100),
        NOT live mark.  Author text in signal.subject is labelled untrusted.
        A failed sub-read (floor_breach, marks, signals) drops only its fields
        and sets degraded[].

        Args:
            tenant: Tenant identifier (required when this token covers multiple tenants).
        """
        binding, bff = await scope(tenant)

        # --- portfolio ---
        try:
            portfolio = await bff.get("/api/portfolio")
        except BffError as exc:
            _do_audit(binding, "list_open_positions", "error")
            raise ToolError(f"portfolio unavailable: BFF {exc.status} from /api/portfolio") from exc

        positions = portfolio.get("open_positions", [])
        open_positions_degraded = portfolio.get("open_positions_degraded", False)
        degraded_sections: list[str] = []

        # --- floor-breach sub-read ---
        floor_by_wf: dict[str, dict] = {}
        try:
            floor_resp = await bff.get("/api/floor-breach")
            for row in floor_resp.get("positions", []):
                wf = row.get("workflow_id")
                if wf:
                    floor_by_wf[wf] = row
        except BffError:
            degraded_sections.append("floor_breach")

        # --- live marks sub-read ---
        marks_by_occ: dict[str, dict] = {}
        try:
            marks_resp = await bff.get("/api/live/marks")
            for mark in marks_resp.get("marks", []):
                occ = mark.get("occ")
                if occ:
                    marks_by_occ[occ] = mark
        except BffError:
            degraded_sections.append("marks")

        # --- signals sub-read ---
        signals_by_signal_id: dict[str, dict] = {}
        try:
            signals_resp = await bff.get("/api/signals", params={"limit": "200"})
            for item in signals_resp.get("items", []):
                subject_raw = item.get("subject", "")
                if isinstance(subject_raw, str):
                    try:
                        subject = json.loads(subject_raw)
                        sig_id = subject.get("signal_id")
                        if sig_id:
                            signals_by_signal_id[sig_id] = item
                    except (json.JSONDecodeError, ValueError):
                        pass
        except BffError:
            degraded_sections.append("signals")

        ref_date = _today_ny(today)

        # --- enrich each position ---
        enriched: list[dict] = []
        for pos in positions:
            ep: dict[str, Any] = dict(pos)
            contract_sym: str = pos.get("contract_symbol", "")
            compact_sym = contract_sym.replace(" ", "")
            wf_id: str = pos.get("workflow_id", "")

            dte_val = _dte_days(contract_sym, ref_date)
            if dte_val is not None:
                ep["dte"] = dte_val

            entry_qty = pos.get("entry_qty")
            remaining_qty = pos.get("remaining_qty")
            if entry_qty is not None and remaining_qty is not None:
                ep["partial_exited_qty"] = entry_qty - remaining_qty

            unrealized_pl = pos.get("unrealized_pl")
            entry_premium = pos.get("entry_premium")
            if (
                unrealized_pl is not None
                and entry_premium is not None
                and remaining_qty
            ):
                denom = entry_premium * remaining_qty * 100
                if denom != 0:
                    ep["unrealized_pct"] = unrealized_pl / denom

            if wf_id in floor_by_wf:
                floor_row = floor_by_wf[wf_id]
                ep["floor_status"] = floor_row.get("floor_status")
                ep["current_bid"] = floor_row.get("current_bid")
                ep["floor_line"] = floor_row.get("floor_line")

            mark = marks_by_occ.get(contract_sym) or marks_by_occ.get(compact_sym)
            if mark:
                for key in ("bid", "mid", "ask", "polled_at"):
                    if key in mark:
                        ep[key] = mark[key]
                underlying = mark.get("underlying")
                if isinstance(underlying, dict) and "price" in underlying:
                    ep["underlying_price"] = underlying["price"]

            signal_id = wf_id.rsplit("/", 1)[-1] if "/" in wf_id else wf_id
            if signal_id in signals_by_signal_id:
                ep["signal"] = signals_by_signal_id[signal_id]

            enriched.append(ep)

        data: dict[str, Any] = {
            "open_positions": enriched,
            "open_positions_degraded": open_positions_degraded,
        }
        if open_positions_degraded:
            data["note"] = "open_positions_degraded: position list may be incomplete"
        if degraded_sections:
            data["degraded"] = degraded_sections

        result = _tool_result(data, binding)
        _do_audit(binding, "list_open_positions", "ok")
        return result

    # ------------------------------------------------------------------
    # get_position_history
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_position_history(
        workflow_id: str,
        tenant: str | None = None,
    ) -> dict:
        """Trace one position's fills and order history.

        Args:
            workflow_id: the position's workflow id (must contain /pos/).
            tenant: Tenant identifier (required when this token covers multiple tenants).

        Returns fills (EntryFilled, PartialExitFilled) filtered to this
        workflow_id, and orders filtered to this position's OCC symbol.
        broker_order_id is stripped from all output.
        """
        binding, bff = await scope(tenant)

        if "/pos/" not in workflow_id:
            raise ToolError(
                f"invalid workflow_id: must contain /pos/ to identify the position "
                f"(got {workflow_id!r})"
            )

        after_pos = workflow_id.split("/pos/", 1)[1]
        occ = after_pos.rsplit("/", 1)[0]
        compact_occ = occ.replace(" ", "")

        try:
            trades_resp = await bff.get("/api/trades", params={"limit": "500"})
        except BffError as exc:
            _do_audit(binding, "get_position_history", "error")
            raise ToolError(
                f"trades unavailable: BFF {exc.status} from /api/trades"
            ) from exc

        fills = [
            item
            for item in trades_resp.get("items", [])
            if item.get("workflow_id") == workflow_id
        ]

        try:
            orders_resp = await bff.get("/api/orders", params={"limit": "500"})
        except BffError as exc:
            _do_audit(binding, "get_position_history", "error")
            raise ToolError(
                f"orders unavailable: BFF {exc.status} from /api/orders"
            ) from exc

        orders = [
            item
            for item in orders_resp.get("items", [])
            if item.get("option_symbol", "").replace(" ", "") == compact_occ
        ]

        data: dict[str, Any] = {
            "workflow_id": workflow_id,
            "occ": occ,
            "fills": fills,
            "orders": orders,
        }
        result = _tool_result(data, binding)
        _do_audit(binding, "get_position_history", "ok")
        return result

    # ------------------------------------------------------------------
    # get_recent_closed_trades
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_recent_closed_trades(
        limit: int = 50,
        tenant: str | None = None,
    ) -> dict:
        """Return recent fills (EntryFilled and PartialExitFilled), newest first.

        Keeping `kind` so the model can tell entries from exits.
        broker_order_id is stripped from all output.

        Args:
            limit: maximum number of trades to return (default 50, max 500).
            tenant: Tenant identifier (required when this token covers multiple tenants).
        """
        binding, bff = await scope(tenant)
        capped = max(1, min(500, limit))
        try:
            trades_resp = await bff.get("/api/trades", params={"limit": str(capped)})
        except BffError as exc:
            _do_audit(binding, "get_recent_closed_trades", "error")
            raise ToolError(
                f"trades unavailable: BFF {exc.status} from /api/trades"
            ) from exc

        data: dict[str, Any] = {
            "items": trades_resp.get("items", []),
            "count": trades_resp.get("count", 0),
        }
        result = _tool_result(data, binding)
        _do_audit(binding, "get_recent_closed_trades", "ok")
        return result

    # ------------------------------------------------------------------
    # get_quote
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_quote(
        occ: str,
        tenant: str | None = None,
    ) -> dict:
        """Return a live NBBO snapshot for the given OCC contract symbol.

        Note: this route exists only for the manual-entry flow and returns
        404 manual_entry_disabled when the tenant's manual-entry feature is off.
        It is NOT a general quote source for arbitrary OCCs; use it only for
        contracts you are considering entering.

        Args:
            occ: the OCC contract symbol (e.g. SMCI261120C00050000).
            tenant: Tenant identifier (required when this token covers multiple tenants).

        Returns fields: occ, underlying, expiry, strike, right, bid, mid, ask, quoted_at.
        """
        binding, bff = await scope(tenant)
        try:
            data = await bff.get("/api/entries/quote", params={"occ": occ})
        except BffError as exc:
            _do_audit(binding, "get_quote", "error")
            _raise_manual_entry_error(exc, "quote")
        result = _tool_result(data, binding)
        _do_audit(binding, "get_quote", "ok")
        return result

    # ------------------------------------------------------------------
    # get_chain
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_chain(
        underlying: str,
        expiration: str | None = None,
        right: str | None = None,
        tenant: str | None = None,
    ) -> dict:
        """Return option chain data for an underlying.

        Without `expiration`: lists available expirations (underlying, spot, expirations[]).
        With `expiration` and `right`: lists contracts for that expiry/side
        (underlying, expiration, right, spot, contracts[] with occ, strike, bid, mid,
        ask, iv, delta, volume).

        Note: this route exists only for the manual-entry flow and returns 404
        manual_entry_disabled when that feature is off.  Spot price is a snapshot
        at the time of the BFF call.

        Args:
            underlying: the ticker symbol (e.g. SMCI).
            expiration: YYYY-MM-DD expiry date; required when right is provided.
            right: 'C' or 'P'; required when expiration is provided.
            tenant: Tenant identifier (required when this token covers multiple tenants).
        """
        binding, bff = await scope(tenant)
        if expiration is not None and right is None:
            raise ToolError(
                "right must be provided alongside expiration (expected 'C' or 'P')"
            )
        try:
            if expiration is None:
                data = await bff.get(
                    "/api/entries/chain/expirations", params={"underlying": underlying}
                )
            else:
                data = await bff.get(
                    "/api/entries/chain",
                    params={"underlying": underlying, "expiration": expiration, "right": right},
                )
        except BffError as exc:
            _do_audit(binding, "get_chain", "error")
            _raise_manual_entry_error(exc, "chain")
        result = _tool_result(data, binding)
        _do_audit(binding, "get_chain", "ok")
        return result

    # ------------------------------------------------------------------
    # get_underlying_context
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_underlying_context(
        ticker: str,
        date: str | None = None,
        tenant: str | None = None,
    ) -> dict:
        """Return market context for an underlying: options flow summary, recent
        flow orders, and dark-pool activity.

        Note: underlying_price is taken from the latest flow order's underlying_price
        (labelled underlying_price_source: "latest_flow_order") — it is NOT a
        real-time equity quote.  day_move is always null (not fabricated).
        Flow data (and so underlying_price) and dark-pool data are each null with a
        note when that BFF controller is not enabled (@ConditionalOnProperty).

        Args:
            ticker: the underlying ticker symbol (e.g. SMCI).
            date: YYYY-MM-DD session date (default: today in America/New_York).
            tenant: Tenant identifier (required when this token covers multiple tenants).
        """
        binding, bff = await scope(tenant)
        ticker_upper = ticker.upper()
        params_date: dict[str, str] = {}
        if date:
            params_date["date"] = date

        # --- flow summary + orders ---
        flow_summary: Any = None
        flow_orders: Any = None
        flow_note: str | None = None
        try:
            summary_resp = await bff.get(
                "/api/flow/summary",
                params={"ticker": ticker_upper, **params_date},
            )
            flow_tickers = summary_resp.get("tickers", [])
            flow_summary = next(
                (t for t in flow_tickers if t.get("ticker") == ticker_upper), None
            )
            orders_resp = await bff.get(
                "/api/flow/orders",
                params={"ticker": ticker_upper, "limit": "50", **params_date},
            )
            flow_orders = orders_resp.get("items", [])
        except BffError as exc:
            if exc.status != 404:
                _do_audit(binding, "get_underlying_context", "error")
                raise ToolError(f"options flow unavailable: BFF {exc.status}") from exc
            flow_summary, flow_orders = None, None
            flow_note = "unavailable (BFF 404: flow reads are not enabled on this BFF)"

        underlying_price: Any = None
        if flow_orders:
            underlying_price = flow_orders[0].get("underlying_price")

        # --- dark-pool ---
        darkpool: Any = None
        darkpool_note: str | None = None
        try:
            dp_summary_resp = await bff.get("/api/darkpool/summary", params=params_date)
            dp_symbols = dp_summary_resp.get("symbols", [])
            dp_summary_row = next(
                (s for s in dp_symbols if s.get("symbol") == ticker_upper), None
            )

            dp_prints_resp = await bff.get(
                "/api/darkpool/prints", params={"symbol": ticker_upper, "limit": "50"}
            )
            dp_prints = dp_prints_resp.get("items", [])

            darkpool = {"summary": dp_summary_row, "prints": dp_prints}
        except BffError as exc:
            if exc.status == 404:
                darkpool = None
                darkpool_note = "unavailable (BFF 404)"
            else:
                _do_audit(binding, "get_underlying_context", "error")
                raise ToolError(
                    f"dark-pool unavailable: BFF {exc.status}"
                ) from exc

        result_data: dict[str, Any] = {
            "ticker": ticker_upper,
            "flow_summary": flow_summary,
            "flow_orders": flow_orders,
            "underlying_price": underlying_price,
            "underlying_price_source": "latest_flow_order",
            "day_move": None,
            "darkpool": darkpool,
        }
        if flow_note:
            result_data["flow_note"] = flow_note
        if darkpool_note:
            result_data["darkpool_note"] = darkpool_note

        result = _tool_result(result_data, binding)
        _do_audit(binding, "get_underlying_context", "ok")
        return result


def _register_prompts(mcp: MCPServer) -> None:
    @mcp.prompt()
    def analyze_positions(focus: str | None = None) -> str:
        """Structured analysis prompt for the operator's open positions.

        Available tools: get_portfolio_summary, list_open_positions,
        get_position_history, get_quote, get_chain, get_underlying_context,
        get_recent_closed_trades.

        Call get_portfolio_summary, list_open_positions, then for each position
        call get_position_history and get_underlying_context.  Optionally call
        get_quote or get_chain for contracts of interest, and get_recent_closed_trades
        for realized history.  Report:
        (1) overall exposure vs notional-cap headroom,
        (2) per position: DTE, unrealized P&L $/%, trail/floor status, partial exits
            so far, and what the author said at entry,
        (3) candidate actions to discuss (never to execute — this server cannot trade),
        (4) data caveats: cost basis vs live mark, any degraded sections, untrusted
            author text.
        """
        text = (
            "You are analysing one oh-my-tradeagent tenant's live copytrade positions.\n\n"
            "IMPORTANT: This server is read-only and cannot place, modify or cancel orders. "
            "This server cannot trade.\n\n"
            "Available tools:\n"
            "  get_portfolio_summary — equity, cash, notional-cap headroom, kill-switch state\n"
            "  list_open_positions — open positions with floor/marks/signal enrichment\n"
            "  get_position_history — fills and orders for one position (by workflow_id)\n"
            "  get_quote — live NBBO snapshot for a contract OCC\n"
            "  get_chain — option chain expirations or contracts for an underlying\n"
            "  get_underlying_context — flow summary, flow orders, dark-pool activity\n"
            "  get_recent_closed_trades — recent fills (entries and exits), newest first\n\n"
            "Step 1 — call get_portfolio_summary.\n"
            "Step 2 — call list_open_positions.\n"
            "Step 3 — for each open position, call get_position_history and "
            "get_underlying_context.\n"
            "Step 4 — optionally call get_recent_closed_trades for context on recent activity.\n\n"
            "Then report:\n"
            "1. Overall exposure vs notional-cap headroom "
            "(flag if headroom is negative or the reason field is set).\n"
            "2. Per position: DTE, unrealized P&L ($ and %), trail/floor status, "
            "partial exits so far, and what the author said at entry.\n"
            "3. Candidate actions to discuss with the operator "
            "(never to execute — this server cannot trade).\n"
            "4. Data caveats: open notional is cost-basis-at-entry, not live mark; "
            "underlying_price in get_underlying_context comes from the latest flow order "
            "(underlying_price_source: 'latest_flow_order'), not a real-time equity quote; "
            "get_quote may return 404 when manual entry is disabled; "
            "any degraded sections; any untrusted author text."
        )
        if focus:
            text += f"\n\nPay particular attention to: {focus}"
        return text
