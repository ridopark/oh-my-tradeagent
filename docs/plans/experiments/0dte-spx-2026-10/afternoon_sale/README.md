# Step 1: afternoon 0DTE premium sale (2026-10-04)

The rules are pre-registered in the `sell_afternoon.py` docstring.

## Instruments
- **SPX:** in-sample, because the 14:00 entry was chosen on SPX.
- **SPY and QQQ:** out-of-sample.
- **IWM:** exploratory only.

## Pre-registered PASS
Iron fly or condor at 14:00, under stress cost, closed at 15:55 on SPY and QQQ: **FAIL for both**.
- Iron fly: −22..−26% of risk per day.
- Iron condor: −3..−4%.
- The exit spread on 4 legs plus the last minutes' moves eat the premium.

## Reported variant: S2 iron condor held to settlement
Shorts ±0.15%, wings ±0.60%. Results at stress cost:

| Entry | SPY | QQQ | SPX (in-sample) | IWM |
|---|---|---|---|---|
| 13:00 | +4.3% t2.4 | +6.5% t3.0 | +2.6% t1.4 | −2.0% |
| 14:00 | +5.5% t3.6 | +7.5% t3.8 | +2.9% t1.7 (H2 ≈ 0) | −0.3% |
| 15:00 | +2.0% t1.8 | +3.3% t2.3 | −0.1% | −0.5% |

Win rate is 67-77%. SPY and QQQ are positive in both halves.

## Why it isn't tradeable as tested
- **SPY/QQQ settle physically.** At 15:30 ET on expiration day, Alpaca stops opening orders on expiring contracts and evaluates the positions. If an ITM long lacks buying power to exercise, Alpaca liquidates it. Short ITM legs are assigned after the close.
- **So the "settle" P&L isn't achievable on SPY/QQQ as tested.**
- **The cash-settled vehicles are SPX and XSP.** XSP has $0 exchange fee under 10 contracts. On those the effect is weaker and not significant (t 1.4-1.7).

## Next valid test
A paper fill-cost test of the S2 condor on XSP/SPX, entered around 13:00-14:00 and held to cash settlement. Log the fill vs mid on every leg.
