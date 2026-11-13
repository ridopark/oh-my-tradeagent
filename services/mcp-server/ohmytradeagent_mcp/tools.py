"""MCP server factory — registers all tools and the prompt.

`build_server(binding, bff)` is the seam between startup and the tool layer.
A2 (remote transport, per-connection binding) will supply a different resolver
and pass a different binding here without rewriting any tool.

Step 2 ships `get_portfolio_summary` and `analyze_positions`.
Steps 3 and 4 add the remaining six tools.
"""
from __future__ import annotations

import logging
from typing import Any

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError

from ohmytradeagent_mcp.bff import BffClient, BffError
from ohmytradeagent_mcp.resolver import TenantBinding
from ohmytradeagent_mcp.sanitize import UNTRUSTED_NOTE, has_untrusted, sanitize

log = logging.getLogger(__name__)


def build_server(binding: TenantBinding, bff: BffClient) -> MCPServer:
    """Return a configured MCPServer with all tools and the prompt registered."""
    mcp = MCPServer(
        name="omt-mcp",
        description=(
            "Read-only operator view of one oh-my-tradeagent tenant's positions, "
            "risk state and market context.  Cannot trade."
        ),
    )

    _register_tools(mcp, binding, bff)
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


def _register_tools(mcp: MCPServer, binding: TenantBinding, bff: BffClient) -> None:
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
