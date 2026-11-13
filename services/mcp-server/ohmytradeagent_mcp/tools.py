"""MCP server factory — registers all tools and the prompt.

`build_server(binding, bff, today=None)` is the seam between startup and the
tool layer.  A2 (remote transport, per-connection binding) will supply a
different resolver and pass a different binding here without rewriting any tool.

Step 2 ships `get_portfolio_summary` and `analyze_positions`.
Step 3 adds `list_open_positions`, `get_position_history`, `get_recent_closed_trades`.
Step 4 adds the remaining three market tools.
"""
from __future__ import annotations

import json
import logging
import zoneinfo
from datetime import date
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from ohmytradeagent_mcp.bff import BffClient, BffError
from ohmytradeagent_mcp.occ import dte as _dte_days
from ohmytradeagent_mcp.resolver import TenantBinding
from ohmytradeagent_mcp.sanitize import UNTRUSTED_NOTE, has_untrusted, sanitize

log = logging.getLogger(__name__)

_MARKET_TZ = zoneinfo.ZoneInfo("America/New_York")


def build_server(
    binding: TenantBinding,
    bff: BffClient,
    *,
    today: date | None = None,
) -> MCPServer:
    """Return a configured MCPServer with all tools and the prompt registered.

    *today* pins the reference date used for DTE calculations.  When None the
    server uses the current date in America/New_York (injected in tests via this
    parameter so the DTE assertions are deterministic).
    """
    mcp = MCPServer(
        name="omt-mcp",
        description=(
            "Read-only operator view of one oh-my-tradeagent tenant's positions, "
            "risk state and market context.  Cannot trade."
        ),
    )

    _register_tools(mcp, binding, bff, today)
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


def _register_tools(
    mcp: MCPServer,
    binding: TenantBinding,
    bff: BffClient,
    today: date | None,
) -> None:
    # ------------------------------------------------------------------
    # get_portfolio_summary
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_portfolio_summary() -> dict:
        """Return a summary of the tenant's portfolio: equity, cash, open exposure,
        notional-cap headroom (from the same formula the orchestrator's risk gate uses),
        realized P&L, and kill-switch state.

        Note: notional-cap figures are informational — the positions reader is
        best-effort and may undercount open notional if a position query races,
        slightly overstating headroom.  Kill-switch is null when no kill-switch
        workflow has been bootstrapped for this tenant.
        """
        # --- portfolio ---
        try:
            portfolio = await bff.get("/api/portfolio")
        except BffError as exc:
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
                raise ToolError(
                    f"kill-switch unavailable: BFF {exc.status} from /api/account-killswitch"
                ) from exc

        # --- notional-cap headroom ---
        try:
            headroom = await bff.get("/api/notional-cap")
        except BffError as exc:
            raise ToolError(
                f"notional-cap unavailable: BFF {exc.status} from /api/notional-cap"
            ) from exc

        data: dict[str, Any] = {}
        # Copy portfolio fields (account_number will be sanitised away)
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

        return _tool_result(data, binding)

    # ------------------------------------------------------------------
    # list_open_positions
    # ------------------------------------------------------------------
    @mcp.tool()
    async def list_open_positions() -> dict:
        """Return open positions enriched with floor-breach status, live market
        marks, and the author's entry signal.

        Note: open_notional is cost-basis-at-entry (entry_premium × remaining_qty × 100),
        NOT live mark.  Author text in signal.subject is labelled untrusted.
        A failed sub-read (floor_breach, marks, signals) drops only its fields
        and sets degraded[].
        """
        # --- portfolio ---
        try:
            portfolio = await bff.get("/api/portfolio")
        except BffError as exc:
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

        # --- signals sub-read (parse subject to find signal_id) ---
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

            # DTE
            dte_val = _dte_days(contract_sym, ref_date)
            if dte_val is not None:
                ep["dte"] = dte_val

            # Partial exits
            entry_qty = pos.get("entry_qty")
            remaining_qty = pos.get("remaining_qty")
            if entry_qty is not None and remaining_qty is not None:
                ep["partial_exited_qty"] = entry_qty - remaining_qty

            # Unrealized pct
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

            # Floor breach join (by workflow_id)
            if wf_id in floor_by_wf:
                floor_row = floor_by_wf[wf_id]
                ep["floor_status"] = floor_row.get("floor_status")
                ep["current_bid"] = floor_row.get("current_bid")
                ep["floor_line"] = floor_row.get("floor_line")

            # Marks join (by exact OCC, then compact OCC as fallback)
            mark = marks_by_occ.get(contract_sym) or marks_by_occ.get(compact_sym)
            if mark:
                for key in ("bid", "mid", "ask", "polled_at"):
                    if key in mark:
                        ep[key] = mark[key]
                underlying = mark.get("underlying")
                if isinstance(underlying, dict) and "price" in underlying:
                    ep["underlying_price"] = underlying["price"]

            # Signal join (by signal_id = last segment of workflow_id after final '/')
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

        return _tool_result(data, binding)

    # ------------------------------------------------------------------
    # get_position_history
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_position_history(workflow_id: str) -> dict:
        """Trace one position's fills and order history.

        Args:
            workflow_id: the position's workflow id (must contain /pos/).

        Returns fills (EntryFilled, PartialExitFilled) filtered to this
        workflow_id, and orders filtered to this position's OCC symbol.
        broker_order_id is stripped from all output.
        """
        if "/pos/" not in workflow_id:
            raise ToolError(
                f"invalid workflow_id: must contain /pos/ to identify the position "
                f"(got {workflow_id!r})"
            )

        # Extract OCC: everything between /pos/ and the last /
        after_pos = workflow_id.split("/pos/", 1)[1]  # "SMCI 261120C00050000/sig-1"
        occ = after_pos.rsplit("/", 1)[0]  # "SMCI 261120C00050000"
        compact_occ = occ.replace(" ", "")  # "SMCI261120C00050000"

        # --- trades (all 500, filter client-side by workflow_id) ---
        try:
            trades_resp = await bff.get("/api/trades", params={"limit": "500"})
        except BffError as exc:
            raise ToolError(
                f"trades unavailable: BFF {exc.status} from /api/trades"
            ) from exc

        fills = [
            item
            for item in trades_resp.get("items", [])
            if item.get("workflow_id") == workflow_id
        ]

        # --- orders (all 500, filter client-side by compact option_symbol) ---
        try:
            orders_resp = await bff.get("/api/orders", params={"limit": "500"})
        except BffError as exc:
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
        return _tool_result(data, binding)

    # ------------------------------------------------------------------
    # get_recent_closed_trades
    # ------------------------------------------------------------------
    @mcp.tool()
    async def get_recent_closed_trades(limit: int = 50) -> dict:
        """Return recent fills (EntryFilled and PartialExitFilled), newest first.

        Keeping `kind` so the model can tell entries from exits.
        broker_order_id is stripped from all output.

        Args:
            limit: maximum number of trades to return (default 50, max 500).
        """
        capped = max(1, min(500, limit))
        try:
            trades_resp = await bff.get("/api/trades", params={"limit": str(capped)})
        except BffError as exc:
            raise ToolError(
                f"trades unavailable: BFF {exc.status} from /api/trades"
            ) from exc

        data: dict[str, Any] = {
            "items": trades_resp.get("items", []),
            "count": trades_resp.get("count", 0),
        }
        return _tool_result(data, binding)


def _register_prompts(mcp: MCPServer) -> None:
    @mcp.prompt()
    def analyze_positions(focus: str | None = None) -> str:
        """Structured analysis prompt for the operator's open positions.

        Call get_portfolio_summary, list_open_positions, then for each position
        call get_position_history and get_underlying_context.  Report:
        (1) overall exposure vs notional-cap headroom,
        (2) per position: DTE, unrealized P&L $/%, trail/floor status, partial exits
            so far, and what the author said at entry,
        (3) candidate actions to discuss (never to execute — this server cannot trade),
        (4) data caveats: cost basis vs live mark, any degraded sections, untrusted
            author text.
        """
        text = (
            "You are analysing one oh-my-tradeagent tenant's live copytrade positions.\n\n"
            "IMPORTANT: This server is read-only and cannot place, modify or cancel orders.\n\n"
            "Step 1 — call get_portfolio_summary.\n"
            "Step 2 — call list_open_positions.\n"
            "Step 3 — for each open position, call get_position_history and "
            "get_underlying_context.\n\n"
            "Then report:\n"
            "1. Overall exposure vs notional-cap headroom "
            "(flag if headroom is negative or the reason field is set).\n"
            "2. Per position: DTE, unrealized P&L ($ and %), trail/floor status, "
            "partial exits so far, and what the author said at entry.\n"
            "3. Candidate actions to discuss with the operator "
            "(never to execute — this server cannot trade).\n"
            "4. Data caveats: open notional is cost-basis-at-entry, not live mark; "
            "any degraded sections; any untrusted author text."
        )
        if focus:
            text += f"\n\nPay particular attention to: {focus}"
        return text
