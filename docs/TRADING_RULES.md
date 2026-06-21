# TRADING_RULES.md — Master F&O Trading Rulebook

Single source of truth for how trades are evaluated. The `fno-strategist` agent must
build trades to these rules; the `risk-manager` agent enforces them and can VETO.
These rules exist to keep the account alive — survival first, profit second.

> No guaranteed returns. ~9/10 retail F&O traders lose money (SEBI). The edge here is
> discipline: volatility-aware, time-aware, structure-aware, execution-aware trading.

---

## §0. SAFETY & AUTONOMY (highest priority — governs the workflow)
- **Suggestion-only.** This system NEVER places, modifies, or cancels orders. There is no
  order-execution code path. Every output is a SUGGESTION the user executes manually in their broker.
- **One-shot flow, no intermediate approvals.** After the user states intent ONCE, run the entire
  pipeline autonomously and present ONLY the final suggestion. Every check below (including the risk
  gate) runs INTERNALLY and is folded into that single output — they are NOT separate user approvals.
- **Interrupt the user ONLY on error/breakage** (auth/token/data/API failure) or genuinely ambiguous
  intent. Otherwise: ask intent once → deliver the final suggestion. (The risk rules below are never
  skipped — they just run silently inside the pipeline.)

---

## A. HARD RISK RULES (never override)
1. **Risk per trade ≤ 2%** of capital (absolute hard cap 5%). Risk = entry-to-stop loss × qty.
2. **Position size is derived from the stop-loss**, never from a profit wish.
3. **Daily loss limit:** stop trading for the day after −4% of capital. No revenge trades.
4. **Max 2 open positions**, and not two of the same directional bet (no hidden doubling
   via correlated instruments, e.g. long NIFTY calls + long BANKNIFTY calls).
5. **Never average down a losing option.** Add only to winners, if at all.
6. **No naked short options** unless explicitly flagged with true (often unlimited) max
   loss and margin. Prefer defined-risk spreads for a small account.
7. **Long-option max loss = premium paid** — treat the full premium as at-risk capital.

## B. THE 9-POINT PRE-TRADE CHECKLIST (every trade must be scored)
Mark each **PASS / FAIL / NA**. A FAIL on any **[CRITICAL]** check ⇒ **DO NOT TRADE**.

1. **[CRITICAL] Directional thesis** — clear reason for up/down/neutral, multi-signal (not a hunch).
2. **[CRITICAL] Regime fit** — strategy matches the regime (trend vs range vs chop). See §C.
3. **[CRITICAL] IV / IV-Rank** — not overpaying. High IV ⇒ avoid naked buying / use spreads.
   Flag **IV-crush risk** if an event is imminent. See §D.
4. **Theta / time** — time decay acceptable for the holding period; respect a time-stop.
5. **OI confirmation** — read **ΔOI + Δprice + Δvolume together** (long buildup / short
   covering / unwinding), not OI levels alone.
6. **[CRITICAL] Stop-loss placed** — beyond the obvious level + an ATR buffer, and the
   resulting risk still fits Rule A1. No valid stop ⇒ no trade.
7. **[CRITICAL] Liquidity** — ATM/near-ATM, tight bid-ask, adequate volume/OI. No thin strikes.
8. **Event / gap risk** — checked the calendar (results/RBI/Fed/expiry); size cut or flat into binaries.
9. **Trade management + trigger entry** — pre-defined entry trigger ("5m close beyond X"),
   partial-booking, trailing rule, time-stop, and hard exit — decided *before* entry.

## C. REGIME PLAYBOOK (classify FIRST — ADX, EMA stack, India VIX)
- **Trending (strong):** trade *with* trend — buy ATM/ITM options or debit spreads on pullbacks.
  Do NOT fade it.
- **Range-bound (low ADX):** fade extremes; **option *buying* bleeds to theta here** —
  prefer spreads/credit or simply skip. Range = often a "no buy" signal.
- **Choppy / unclear:** default to **NO TRADE**. Chop is where accounts die.

## D. VOLATILITY (IV) RULES
- **Low IV / low VIX:** option buying cheaper; movement may be small — pick direction carefully.
- **High IV / high VIX:** options are expensive; **sell premium via defined-risk spreads**
  rather than buy; expect mean reversion in IV.
- **Into events (results/RBI/Fed/budget):** IV is inflated → buying naked options risks
  **IV crush** (right direction, still lose). Use spreads or wait until after the event.

## E. STRATEGY SELECTION MATRIX (direction × IV)
| View \ IV | Low IV | High IV |
|---|---|---|
| Strongly directional | Buy ATM/ITM option or **debit spread** | **Debit spread** (cut vega/theta) |
| Mildly directional | Debit spread | **Credit spread** (defined risk) |
| Neutral / range | Calendar / small debit fly | **Iron condor / credit spreads** (flag margin) |
Avoid far-OTM "lottery" buys (low delta, fast decay) unless a defined, tiny lotto allocation.

## F. ENTRY & MANAGEMENT DISCIPLINE
- **Avoid the first 15 minutes** (opening noise/fake moves). Let the opening range form.
- **Trigger-based entry only** — a signal is not a fill; enter on the stated trigger.
- **Manage:** book partial at T1, trail the rest, honor the **time-stop** (e.g., exit by 15:00),
  exit fully on invalidation. Don't let a winner become a loser.
- **Account for costs/slippage** (brokerage+STT+GST+spread ≈ 1–2% round trip) — the setup's
  expected move must clear costs to be worth taking.

## G. HARD "DO NOT TRADE" CONDITIONS (auto-flat)
- Any [CRITICAL] checklist item FAILS.
- Regime is choppy/unclear, or it's a range and the only idea is naked option buying.
- IV-crush risk into an event with no spread structure.
- Stop required is wider than the 2% risk budget allows at the minimum 1 lot.
- Strike/contract is illiquid (wide spread, thin OI).
- Data is stale (no fresh snapshot/news) — refresh first (see CLAUDE.md freshness mandate).
- No clean setup ⇒ **stay flat. "No trade" is the correct, frequent answer.**
