# PIPELINE.md — Orchestrated Daily Trade Pipeline

One trigger runs the whole chain. The deterministic **data stage** is a script
(`src/run_pipeline.py`); the **AI stages** are specialist agents orchestrated by Claude
at the top level (sub-agents can't spawn sub-agents, so the main session coordinates them
and runs the independent stages in parallel).

## Trigger & one-shot flow (suggestion-only)
The user pastes the login URL (and/or says "run the pipeline" / "give me today's trade").
**Then:**
1. Exchange the token (internal). **Ask the user their intent ONCE** — category (F&O intraday /
   positional / etc.), underlying, capital, and any bullish/bearish lean.
2. Run the ENTIRE pipeline below **autonomously and silently — NO intermediate approvals, no
   stage-by-stage check-ins, no permission requests between stages.**
3. Present **ONLY the final suggestion** (a trade card or "stay flat"). The risk gate runs
   INTERNALLY and is folded into that one output.
4. **This system only SUGGESTS — it never places/modifies orders.** The user executes manually.
5. **Interrupt the user ONLY** if something errors/breaks (token/data/API failure) or intent is
   genuinely ambiguous.

## Stage graph
```
            ┌─────────────────────────────────────────────┐
 STAGE 0/1  │ src/run_pipeline.py  ["<redirect-url>"]      │  (script, deterministic)
 DATA       │  token -> market_snapshot -> readiness report│
            └───────────────────────┬─────────────────────┘
                                    │ data/snapshot_*.json ready
                    ┌───────────────┴───────────────┐
 STAGE A            ▼ (run in PARALLEL)              ▼
 CONTEXT     ┌─────────────┐                  ┌─────────────┐
             │ news-scanner│                  │ backtester  │ (optional but recommended:
             │  -> bias    │                  │ -> edge?    │  validate the setup type)
             └──────┬──────┘                  └──────┬──────┘
                    └───────────────┬───────────────┘
 STAGE B                            ▼
 BUILD                       ┌──────────────┐
                             │fno-strategist│  uses live data + option chain +
                             │ -> trade card│  news bias + backtest + precedent;
                             └──────┬───────┘  scores the 9-point checklist
 STAGE C                            ▼
 GATE                        ┌──────────────┐
                             │ risk-manager │  APPROVE / APPROVE-WITH-CHANGES / VETO
                             └──────┬───────┘
                                    ▼
 OUTPUT          Final trade card (or "stay flat") shown to the user
                                    │
 STAGE D (after the user actually enters the trade)
 MANAGE          position-monitor (watch stop/target/time-stop) + trade-journal (log outcome)
```

## Stage details (inputs -> outputs)
| Stage | Runs | Needs | Produces |
|---|---|---|---|
| 0/1 Data | `src/run_pipeline.py` | token (or pasted URL) | fresh `data/snapshot_*.json` + readiness report |
| A Context | `news-scanner` ∥ `backtester` | snapshot + web | bias + key levels; historical edge verdict |
| B Build | `fno-strategist` | snapshot, bias, backtest | scored trade card (entry/SL/targets/sizing) |
| C Gate | `risk-manager` | the trade card | APPROVE / CHANGES / VETO |
| D Manage | `position-monitor`, `trade-journal` | filled order | live alerts; logged P&L/expectancy |

## Orchestration rules (for the main session) — run autonomously, surface only the end
1. **Ask intent ONCE** (after the token is obtained), then proceed without further questions.
2. **Run Stage 0/1.** If `run_pipeline.py` fails (no token / segment / snapshot error), STOP and
   tell the user (this is an allowed interrupt) — never analyse on missing data (freshness mandate).
3. **Launch Stage A agents in ONE message** (parallel) — `news-scanner` and `backtester`.
4. **Feed both into `fno-strategist`** (Stage B). "No clean setup" → present stay-flat; never force a trade.
5. **Run `risk-manager` INTERNALLY** (Stage C) and **fold its verdict into the single final card** —
   do NOT present it as a separate APPROVE/VETO approval step. A VETO just means the final suggestion
   is "no trade / stay flat", with the reason.
6. **Present ONLY the final suggestion** to the user — no intermediate stage check-ins or approvals.
   The system SUGGESTS only; the user places any order manually.
7. **Stage D only after the user says they entered** the trade (`position-monitor` + `trade-journal`).
8. Interrupt the user ONLY on error/breakage or genuinely ambiguous intent.

## Notes
- Stages A→C need a valid token + active Upstox segments (live as of 2026-06-16).
- `backtester` (Stage A) is optional for speed but recommended before trusting a new setup.