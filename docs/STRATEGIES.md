# STRATEGIES.md — F&O Options Strategy Playbook

The structures the `fno-strategist` chooses from. Pick by **direction × IV × regime**
(see `TRADING_RULES.md` §C–E). Default to **defined-risk** structures for a small account.
Every structure still passes the 9-point checklist and the `risk-manager` gate.

> Legend: max loss/profit are per the structure; always compute in ₹ for your lots.
> "IV" = implied volatility regime (low vs high / IV-Rank). VIX = India VIX.

---

## Directional

### 1. Long Call / Long Put
- **Use when:** strongly directional + **LOW IV** + trending regime. Intraday momentum.
- **Risk:** max loss = premium paid; **theta bleeds daily**, worse near expiry.
- **Avoid:** buying into events (IV crush) or in a range (theta death). No far-OTM lottery.

### 2. Bull Call Spread / Bear Put Spread (DEBIT spread)
- **Use when:** directional but want to **cut cost, theta and vega** (esp. higher IV).
- **Build:** buy ATM/ITM, sell further OTM same expiry. Max loss = net debit; capped profit.
- **Best default directional structure for a small account.**

### 3. Bull Put Spread / Bear Call Spread (CREDIT spread)
- **Use when:** mildly directional / want time on your side + **HIGH IV** (sell premium).
- **Build:** sell closer strike, buy further OTM (defines risk). Max loss = spread − credit.
- **Risk:** margin required; loss > profit on a wrong move — respect the stop.

---

## Volatility (non-directional)

### 4. Long Straddle / Strangle (LONG vega)
- **Use when:** you expect a **big move but unknown direction**, and **IV is LOW** before it.
- **Build:** buy ATM call+put (straddle) or OTM call+put (strangle).
- **Risk:** the killer is **IV crush** — if you buy when IV is already high (pre-event), the
  move can come and you still lose. Needs a move bigger than both premiums + costs.

### 5. Short Straddle / Strangle (SHORT vega)
- **Use when:** expect range / IV to fall, **HIGH IV**. Advanced only.
- **⚠️ NAKED = (near) UNLIMITED RISK + heavy margin.** Per `TRADING_RULES.md` §A6, only with
  explicit max-loss/margin and ideally converted to a defined-risk Iron version (below).

### 6. Iron Condor / Iron Fly (defined-risk neutral)
- **Use when:** range-bound + **HIGH IV** (sell premium with capped risk).
- **Build:** sell a strangle/straddle, buy further-OTM wings to cap loss.
- **Risk:** max loss = wing width − net credit; loses if price breaks the range. Pin/gamma
  risk near expiry. The **preferred neutral structure** vs naked shorts.

---

## Time / term-structure

### 7. Calendar (Horizontal) Spread
- **Use when:** you want to **exploit IV term structure / an upcoming IV crush** — sell the
  near (rich, fast-decaying) expiry, buy the far expiry, same strike.
- **Edge:** profits from near-leg theta and/or far-leg IV; a real way to *play* events
  instead of being a victim of IV crush.
- **Risk:** vega/skew sensitive; needs liquid both-expiry strikes; defined-ish but nuanced.

### 8. Ratio / Backspread (ADVANCED — flag risk)
- Unbalanced legs → can carry **undefined risk** on one side. Only with explicit max-loss
  and a clear reason; otherwise skip.

---

## Expiry-day / 0DTE tactics (HIGH CAUTION)
- **Gamma explodes** near expiry — small underlying moves swing option P&L violently.
- Theta is steepest; price gravitates toward **max-pain**; pin risk at round strikes.
- For a small account: **mostly avoid naked 0DTE buying.** If used, tiny size, hard
  time-stops, defined-risk spreads only, and treat it as the highest-risk bucket.

---

## Selection shortcut
| View \\ IV | Low IV | High IV |
|---|---|---|
| Strong direction | Long option / **debit spread** | **Debit spread** |
| Mild direction | **Debit spread** | **Credit spread** |
| Neutral / range | Calendar / small fly | **Iron condor / iron fly** |
| Big move, unknown dir | **Long straddle/strangle** | Calendar (avoid buying rich vol) |

Always: liquid strikes, stop + ATR buffer, ≤2% risk, time-stop, exit plan. When nothing
fits cleanly → **stay flat.**
