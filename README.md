# Live-Data — Upstox F&O Live Analysis App

A personal pipeline that fetches **live Indian market data** from Upstox, analyses it
(technicals + option chain + latest news + historical precedent), and produces
**risk-managed F&O trade ideas**. Built to be honest and disciplined, not a profit promise.

A new Claude Code chat opened in this folder already knows the whole setup (via
`CLAUDE.md`, the agents, and memory) — you never need to re-explain it.

## Project structure
```
Live-Data/
├── README.md / CLAUDE.md / requirements.txt / .env / .gitignore   (root)
├── src/        all Python scripts (run from the project root)
├── docs/       TRADING_RULES.md, STRATEGIES.md, PIPELINE.md, WORKFLOW.md
├── config/     positions.example.json  (copy -> positions.json)
├── data/       market data output (gitignored)
├── scratch/    throwaway agent/analysis scripts (gitignored — safe to empty)
└── .claude/agents/   the 8 agents
```

---

## 1. One-time setup (already done)
- **Python 3.14** installed; project virtualenv in `.venv/` (run Python as `.\.venv\Scripts\python.exe`).
- Dependencies installed (`upstox-python-sdk`, `websockets`, `protobuf`, `python-dotenv`).
- `.env` holds your `UPSTOX_API_KEY` / `UPSTOX_API_SECRET` / `UPSTOX_REDIRECT_URI` (gitignored).
- PowerShell execution policy set so the venv activates.

**Prerequisite on Upstox's side:** your account must have **active trading segments**.
If you ever see error `UDAPI100058`, reactivate them in the Upstox app/web first — nothing
here works until then. (API key + secret are already confirmed valid.)

---

## 2. Daily routine

**1. Open the project & a terminal**
- VS Code → open folder `Live-Data` → start a new Claude Code chat.
- Activate the environment:
  ```powershell
  .\.venv\Scripts\Activate.ps1
  ```

**2. Get today's access token** (tokens expire ~3:30 AM IST daily)
- Tell Claude *"Give me my Upstox login URL"* → log in → paste back the
  `https://127.0.0.1/?code=...` URL (the "site can't be reached" page is normal — the
  code is in the address bar). Claude runs `src/get_token.py`.
- Or do it yourself: `python src/auth.py`.

**3. Pull fresh data** (freshness is mandatory — see §3)
- `python src/market_snapshot.py` — status, candles, option chain (OI/IV/greeks), news.
- During market hours, for live ticks: `python src/streamer.py` (leave running).

**4. Ask for a trade (orchestrated pipeline)**
- *"Run the pipeline"* or *"Give me an F&O trade for today — ₹30,000, intraday."*
- This runs `src/run_pipeline.py` (token → snapshot → readiness), then Claude orchestrates
  the agents per `docs/PIPELINE.md`: **news-scanner ∥ backtester → fno-strategist →
  risk-manager** → a checked, gated trade card. "No clean setup → stay flat" is a valid answer.

**5. After entry / cleanup**
- *"watch my position"* → `position-monitor`; *"log this trade"* → `trade-journal`.
- *"clean up the data"* / *"keep the last 10 files"* → `data-janitor`.

---

## 3. The trading strategy (the important part — don't lose this)

### 3.1 Honest expectations
- ~**9 out of 10** retail F&O traders lose money (SEBI); average losers lost **over ₹1 lakh**.
- **₹30,000 is a small/undercapitalised account** for F&O: it's below index *futures* margin
  (~₹1.2–1.7 lakh), so you're limited to **option buying / small spreads** — the hardest corner.
- A realistic *aspirational* target for a disciplined trader is **single-digit % per year**
  with big drawdowns — **NOT** 15–20% per trade/month. Anyone promising that is selling something.
- The edge is **discipline and risk control**, not prediction. The tool helps you *not blow up*
  and take only clean setups — that is the only durable edge.

### 3.2 The 9 failure modes that kill trades — and the defenses
Even a *directionally-correct* trade fails for these reasons. Each is now an enforced check:

1. **IV crush** — buying rich options into an event; the move happens but IV collapses and you
   still lose. → Check IV/IV-Rank; use **spreads** into events, don't buy naked.
2. **Theta decay** — long options bleed daily, faster near expiry. → Respect a **time-stop**;
   don't hold low-conviction longs overnight.
3. **Wrong regime** — trend strategy in a range (whipsaw) / fade in a strong trend (run over).
   → **Classify regime first** (ADX/EMA/VIX); in a tight range, buying = theta death → skip.
4. **Misreading OI** — long buildup vs short covering vs unwinding look alike if you only see
   levels. → Read **ΔOI + Δprice + Δvolume together**.
5. **Stop-hunting** — stops at obvious round/swing levels get swept. → Place stops **beyond**
   structure + an **ATR buffer**; size to that wider stop.
6. **Liquidity/slippage** — thin strikes, wide spreads cost 2–5% instantly. → Trade **liquid,
   ATM/near-ATM** strikes; check the spread before entry.
7. **Event/gap risk** — Indian markets gap on global cues; stops can't protect gaps. → Know the
   **event calendar**; cut size or stay flat into binaries.
8. **No trade-management plan** — winners turn to losers; averaging down on losers. → Pre-decide
   **partial booking, trailing, time-stop, hard exit** before entry. Never average down a loser.
9. **Signal-to-fill lag** — analysis runs on a snapshot; price moves before you act. → Use
   **trigger entries** ("enter only on a 5-min close beyond X"), not "enter now".

**The pattern:** stop thinking only "up or down". Think **direction + IV + time + structure + exit.**

### 3.3 Core rules (full version in `docs/TRADING_RULES.md`)
- **Risk ≤ 2%** of capital per trade (hard cap 5%); **size from the stop-loss**, not a profit wish.
- **Daily loss limit −4%** → stop for the day; no revenge trades.
- **Max 2 positions**, no doubling a correlated bet; **no averaging down losers**.
- **No naked shorts** without flagging true max loss; prefer **defined-risk spreads**.
- **9-point checklist** scored on every trade; any **[CRITICAL] fail ⇒ DO NOT TRADE**.
- **"No clean setup → stay flat"** is a valid, frequent, correct outcome.

### 3.4 The decision pipeline (agents)
```
backtester     → does this setup even have a historical edge?
news-scanner   → latest same-day news/cues → bias + key levels
fno-strategist → live data + option chain + news + precedent → trade card (9-point checklist)
risk-manager   → independent re-check vs docs/TRADING_RULES.md → APPROVE / CHANGES / VETO
position-monitor → watches the live trade vs stop/target/time-stop
trade-journal  → logs the outcome → real win-rate / expectancy / cost drag
```
Idea-generation (`fno-strategist`) is deliberately separated from risk control
(`risk-manager`) — like a real trading desk. No trade is final until it clears the risk gate.

---

## 4. Files & agents reference
| Script (`src/`) | Purpose |
|---|---|
| `src/run_pipeline.py ["<url>"]` | **One-command pipeline data stage**: token → snapshot → readiness report (see `docs/PIPELINE.md`) |
| `src/auth.py` | Interactive daily Upstox login |
| `src/get_token.py "<redirect-url>"` | Non-interactive token exchange (paste-the-URL flow) |
| `src/market_snapshot.py` | Fetch fresh candles / option chain (OI/IV/greeks) / news / status |
| `src/streamer.py` | Live WebSocket V3 tick stream → `data/` (market hours) |
| `src/backtest.py` | Validate a setup's historical edge (win-rate/expectancy/PF/drawdown) |
| `src/monitor_positions.py` | Watch open trades in `config/positions.json` vs stop/target/time-stop (alert-only) |
| `src/journal.py add\|stats` | Log trades → `trade_journal.csv`; compute real win-rate/expectancy/costs |
| `src/cleanup_data.py` | Safe, path-guarded cleanup of `data/` (`--keep-latest`, `--older-than`, `--pycache`, `--dry-run`) |
| `docs/TRADING_RULES.md` | Master rulebook (risk rules, 9-point checklist, regime/IV playbook) |
| `docs/STRATEGIES.md` | Options strategy playbook (spreads, straddle/strangle, condor, calendar, 0DTE) |
| `docs/PIPELINE.md` | Orchestrated pipeline stage graph + rules |
| `docs/WORKFLOW.md` | Full system diagram (Mermaid + ASCII) |

| Agent (`.claude/agents/`) | Role |
|---|---|
| `news-scanner` | Latest same-day news → bias |
| `fno-strategist` | Builds the trade card per the rulebook + `docs/STRATEGIES.md` |
| `risk-manager` | Independent APPROVE/VETO risk gate |
| `backtester` | Proves/disproves a setup's historical edge before you trust it |
| `position-monitor` | Watches open trades vs stop/target/time-stop, alerts you |
| `trade-journal` | Scorekeeper: real win-rate, expectancy, cost drag |
| `market-analyst` | General technical analysis engine |
| `data-janitor` | Safe data cleanup on request |

---

## 5. Troubleshooting
- **`UDAPI100058` "no segments active"** → reactivate trading segments in the Upstox app.
- **"site can't be reached" after login** → normal; copy the URL from the address bar.
- **Token errors / 401 next day** → the daily token expired (~3:30 AM IST); re-authenticate.
- **`python` not found in a terminal** → it opened before install or venv isn't active;
  reopen the terminal and run `.\.venv\Scripts\Activate.ps1`.
- **Activation blocked** → `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` (already set).

---

## 6. Reality check (read once, then again before risking money)
F&O is leveraged and high-risk. This app is **risk-managed decision-support, not financial
advice and not guaranteed profit**. Consider **paper-trading first** (log trades with
`src/journal.py`) to prove positive expectancy after costs before risking real capital.
You make every final decision and trade at your own risk.
