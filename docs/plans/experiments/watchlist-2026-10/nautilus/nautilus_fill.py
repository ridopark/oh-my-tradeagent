"""Nautilus spike: does a limit buy at MID fill on the author's 0DTE contracts? Our collector's 1-min NBBO -> QuoteTick ->
SimulatedExchange (L1). One engine per (contract-day, entry). Independent pandas replay of the same rule alongside."""
import gzip, json, math, sys, warnings; warnings.filterwarnings("ignore")
import pandas as pd, numpy as np
from decimal import Decimal
from nautilus_trader.backtest.engine import BacktestEngine
from nautilus_trader.config import BacktestEngineConfig, StrategyConfig, LoggingConfig
from nautilus_trader.backtest.models import FillModel
from nautilus_trader.model.data import QuoteTick
from nautilus_trader.model.enums import AccountType, OmsType, OrderSide, AssetClass, OptionKind, TimeInForce
from nautilus_trader.model.identifiers import InstrumentId, Symbol, Venue, TraderId
from nautilus_trader.model.instruments import OptionContract
from nautilus_trader.model.objects import Money, Price, Quantity, Currency
from nautilus_trader.trading.strategy import Strategy
ET = "America/New_York"; USD = Currency.from_str("USD"); VENUE = Venue("ALPACA"); CANCEL_MIN = 15
def instrument(occ, day):
    u = occ[:3]; kind = OptionKind.CALL if occ[9] == "C" else OptionKind.PUT; k = int(occ[10:]) / 1000
    d = pd.Timestamp(day, tz=ET)
    return OptionContract(instrument_id=InstrumentId(Symbol(occ), VENUE), raw_symbol=Symbol(occ), asset_class=AssetClass.EQUITY, currency=USD,
        price_precision=2, price_increment=Price.from_str("0.01"), multiplier=Quantity.from_int(100), lot_size=Quantity.from_int(1), underlying=u,
        option_kind=kind, strike_price=Price.from_str(f"{k:.2f}"), activation_ns=(d.replace(hour=4)).value, expiration_ns=(d.replace(hour=16, minute=15)).value, ts_event=0, ts_init=0)
def quotes(occ, day, inst):
    rows = [json.loads(l) for l in gzip.open(f"oq/{occ[:3]}/{day}.ndjson.gz", "rt")]
    q = pd.DataFrame([r for r in rows if r["sym"] == occ]).rename(columns={"as": "asz"}); q["t"] = pd.to_datetime(q.ts); q = q[(q.bp > 0) & (q.ap >= q.bp)].sort_values("t").drop_duplicates("t")
    ticks = [QuoteTick(inst.id, Price.from_str(f"{r.bp:.2f}"), Price.from_str(f"{r.ap:.2f}"), Quantity.from_int(max(int(r.bs), 1)), Quantity.from_int(max(int(r.asz), 1)),
                       r.t.tz_convert("UTC").value, r.t.tz_convert("UTC").value) for r in q.itertuples()]
    return ticks, q
class MidLimitConfig(StrategyConfig, frozen=True):
    instrument_id: str; entry_ns: int; cancel_ns: int
RESULT = {}
class MidLimit(Strategy):
    def __init__(self, config):
        super().__init__(config); self.iid = InstrumentId.from_str(config.instrument_id); self.order = None
    def on_start(self):
        self.subscribe_quote_ticks(self.iid)
        self.clock.set_time_alert_ns("entry", self.config.entry_ns, self.on_entry); self.clock.set_time_alert_ns("cancel", self.config.cancel_ns, self.on_cancel)
    def on_entry(self, ev):
        q = self.cache.quote_tick(self.iid)
        if q is None: RESULT.update(status="no_quote"); return
        mid = math.floor((float(q.bid_price) + float(q.ask_price)) / 2 * 100) / 100
        RESULT.update(status="submitted", entry_bid=float(q.bid_price), entry_ask=float(q.ask_price), limit=mid, quote_ts=q.ts_event)
        self.order = self.order_factory.limit(self.iid, OrderSide.BUY, Quantity.from_int(1), Price.from_str(f"{mid:.2f}"), time_in_force=TimeInForce.GTC)
        self.submit_order(self.order)
    def on_order_filled(self, ev):
        RESULT.update(status="filled", fill_px=float(ev.last_px), fill_ns=ev.ts_event)
    def on_cancel(self, ev):
        if self.order is not None and RESULT.get("status") == "submitted": self.cancel_order(self.order); RESULT.update(status="unfilled")
def run_one(occ, day, entry_min, ticks, inst):
    RESULT.clear()
    engine = BacktestEngine(BacktestEngineConfig(trader_id=TraderId("FILLTEST-001"), logging=LoggingConfig(log_level="ERROR")))
    engine.add_venue(venue=VENUE, oms_type=OmsType.NETTING, account_type=AccountType.CASH, base_currency=USD, starting_balances=[Money(100_000, USD)],
                     fill_model=FillModel(prob_fill_on_limit=1.0, prob_slippage=0.0))
    engine.add_instrument(inst); engine.add_data(ticks)
    t0 = pd.Timestamp(day, tz=ET).replace(hour=9, minute=30) + pd.Timedelta(minutes=int(entry_min))
    engine.add_strategy(MidLimit(MidLimitConfig(instrument_id=str(inst.id), entry_ns=t0.tz_convert("UTC").value, cancel_ns=(t0 + pd.Timedelta(minutes=CANCEL_MIN)).tz_convert("UTC").value)))
    engine.run(); engine.dispose(); r = dict(RESULT); r["entry_ns"] = t0.tz_convert("UTC").value; return r
def pandas_replay(q, entry_min, day):
    t0 = pd.Timestamp(day, tz=ET).replace(hour=9, minute=30) + pd.Timedelta(minutes=int(entry_min)); before = q[q.t <= t0]
    if before.empty: return dict(status="no_quote")
    e = before.iloc[-1]; mid = math.floor((e.bp + e.ap) / 2 * 100) / 100; after = q[(q.t > t0) & (q.t <= t0 + pd.Timedelta(minutes=CANCEL_MIN))]
    hit = after[after.ap <= mid]
    return dict(status="filled" if len(hit) else "unfilled", fill_min=((hit.t.iloc[0] - t0).total_seconds() / 60) if len(hit) else np.nan, entry_ask=e.ap, limit=mid,
                ask_5m=(after[after.t <= t0 + pd.Timedelta(minutes=5)].ap.iloc[-1] if len(after) else np.nan))
if __name__ == "__main__":
    rng = np.random.default_rng(25); T = pd.read_csv("nautilus_trades.csv"); out = []
    for r in T.itertuples():
        inst = instrument(r.sym, r.d); ticks, q = quotes(r.sym, r.d, inst)
        entries = [("author", int(r.entry_min))] + [("random", int(x)) for x in rng.integers(30, 300, 5)]
        for who, em in entries:
            n = run_one(r.sym, r.d, em, ticks, inst); p = pandas_replay(q, em, r.d)
            fill_min = (n["fill_ns"] - n["entry_ns"]) / 6e10 if n.get("status") == "filled" else np.nan
            out.append(dict(d=r.d, sym=r.sym, who=who, entry_min=em, n_status=n.get("status"), n_fill_min=fill_min, n_limit=n.get("limit"), n_entry_ask=n.get("entry_ask"), n_fill_px=n.get("fill_px"),
                            p_status=p.get("status"), p_fill_min=p.get("fill_min"), p_limit=p.get("limit"), ask_5m=p.get("ask_5m")))
        print(f"{r.d} {r.sym}: author {out[-6]['n_status']} ({out[-6]['n_fill_min']}) | pandas {out[-6]['p_status']}", flush=True)
    R = pd.DataFrame(out); R.to_csv("nautilus_fill_results.csv", index=False)
    print("\nagreement nautilus vs pandas on fill status:", f"{(R.n_status == R.p_status).mean():.0%}", "| on limit price:", f"{np.isclose(R.n_limit.astype(float), R.p_limit.astype(float), equal_nan=True).mean():.0%}")
    for who, g in R.groupby("who"):
        f = g[g.n_status.isin(["filled","unfilled"])]; filled = f.n_status == "filled"
        print(f"{who:7s} n={len(f)} | mid-limit filled within 1m {((f.n_fill_min<=1)).mean():.0%}, 5m {((f.n_fill_min<=5)).mean():.0%}, 15m {filled.mean():.0%} | "
              f"saving when filled vs paying the ask: {((f.n_entry_ask - f.n_fill_px)/f.n_entry_ask)[filled].mean()*100:.1f}% of premium | "
              f"when UNFILLED, ask after 5m vs entry ask: {((f.ask_5m - f.n_entry_ask)/f.n_entry_ask)[~filled].mean()*100:+.1f}%")
