# WORKFLOW.md — System Diagram

The end-to-end workflow of the Live-Data F&O analysis app: auth → data → agent pipeline
→ gated trade card → post-trade management.

> **Viewing the diagram:** the Mermaid chart below renders automatically on GitHub and in
> VS Code with the *"Markdown Preview Mermaid Support"* extension (Ctrl+Shift+V to preview).
> An ASCII version follows for plain terminals.

## Flowchart (Mermaid)

```mermaid
flowchart TD
    %% ===== 1. AUTH =====
    subgraph AUTH["🔐 1. Daily Auth — you, once per day"]
        U(["You"]) -->|open login URL| LOGIN["Upstox browser login + 2FA"]
        LOGIN -->|redirect with code| PASTE["paste redirect URL into chat"]
    end

    %% ===== 2. DATA LAYER =====
    subgraph DATA["📡 2. Data Layer — Java tools (jar subcommands)"]
        RP["pipeline<br/>(one-command driver)"]
        GT["get-token / auth"]
        TOK[".access_token"]
        MS["snapshot<br/>REST: status · candles ·<br/>option chain OI/IV/greeks<br/>+ digest (EMA·RSI·ATR · PCR · max-pain · walls)"]
        ST["stream<br/>WebSocket V3 live ticks"]
        DDIR[("data/<br/>snapshot_*.json<br/>market_data_*.json")]
    end

    PASTE --> RP --> GT --> TOK
    TOK --> MS --> DDIR
    ST --> DDIR
    RP -. triggers .-> MS

    %% ===== RULEBOOKS =====
    subgraph RULES["📕 Rulebooks — docs/"]
        TR["TRADING_RULES.md<br/>9-point checklist · risk caps"]
        STR["STRATEGIES.md<br/>option structures"]
    end

    %% ===== 3. ANALYSIS PIPELINE =====
    subgraph PIPE["🧠 3. Analysis Pipeline — agents, Claude-orchestrated"]
        BT["backtester<br/>historical edge?"]
        NS["news-scanner<br/>same-day bias + levels"]
        FS["fno-strategist<br/>build trade card"]
        RM{"risk-manager<br/>APPROVE / VETO"}
        OUT["✅ Trade Card<br/>or 🛑 Stay Flat"]
    end

    DDIR --> BT
    DDIR --> NS
    BT -->|edge verdict| FS
    NS -->|bias| FS
    TR -.governs.-> FS
    STR -.governs.-> FS
    FS -->|scored card| RM
    TR -.governs.-> RM
    RM -->|APPROVE / CHANGES| OUT
    RM -->|VETO| OUT

    %% ===== 4. POST-TRADE =====
    subgraph MANAGE["📈 4. After You Enter — post-trade"]
        PM["position-monitor<br/>watch stop / target / time-stop"]
        TJ["trade-journal<br/>win-rate · expectancy · cost drag"]
    end

    OUT -->|you place the order in broker| PM
    PM -->|on exit| TJ

    %% ===== SUPPORT =====
    subgraph SUP["🧹 Support — on demand"]
        DJ["data-janitor<br/>safely clean data/"]
        MA["market-analyst<br/>general technical analysis"]
    end
    DDIR -.-> DJ
    DDIR -.-> MA

    %% ===== styles =====
    classDef human fill:#fde68a,stroke:#b45309,color:#111;
    classDef script fill:#bfdbfe,stroke:#1e40af,color:#111;
    classDef agent fill:#bbf7d0,stroke:#15803d,color:#111;
    classDef data fill:#e9d5ff,stroke:#6b21a8,color:#111;
    classDef rule fill:#fecaca,stroke:#991b1b,color:#111;
    classDef gate fill:#fed7aa,stroke:#9a3412,color:#111;
    classDef out fill:#fef08a,stroke:#a16207,color:#111;

    class U,LOGIN,PASTE human;
    class RP,GT,MS,ST script;
    class BT,NS,FS,PM,TJ,DJ,MA agent;
    class TOK,DDIR data;
    class TR,STR rule;
    class RM gate;
    class OUT out;
```

## ASCII fallback

```
        YOU (browser login + 2FA, once/day)
                     │  paste redirect ?code=...
                     ▼
        ┌─────────────────────────────────────────┐
        │  DATA LAYER (Java jar subcommands)        │
        │  pipeline                                 │
        │     └─ get-token ─► .access_token         │
        │     └─ snapshot ───┐                       │
        │        stream ─────┴─► data/*.json         │
        └───────────────────────┬───────────────────┘
                                │  fresh data ready
                ┌───────────────┴───────────────┐
                ▼ (PARALLEL)                     ▼
        ┌──────────────┐                 ┌──────────────┐
        │ news-scanner │                 │  backtester  │
        │  -> bias     │                 │  -> edge?    │
        └──────┬───────┘                 └──────┬───────┘
               └───────────────┬────────────────┘
                               ▼     ◄── docs/TRADING_RULES.md
                       ┌──────────────┐  ◄── docs/STRATEGIES.md
                       │fno-strategist│
                       │ -> trade card│
                       └──────┬───────┘
                              ▼     ◄── docs/TRADING_RULES.md
                       ┌──────────────┐
                       │ risk-manager │  APPROVE / CHANGES / VETO
                       └──────┬───────┘
                              ▼
                  ✅ TRADE CARD  or  🛑 STAY FLAT
                              │  (you place the order)
                              ▼
                 position-monitor ──► trade-journal
                 (live stop/target)   (win-rate / expectancy)

   Support (any time): data-janitor (cleanup) · market-analyst (general TA)
```

## Legend
- 🟨 **Human step** (only you can do): browser login, and placing the actual order.
- 🟦 **Java tool** (deterministic; a subcommand of the built jar).
- 🟩 **Agent** (Claude-orchestrated reasoning).
- 🟪 **Data store** (`data/`, gitignored).
- 🟥 **Rulebook** (`docs/`) that governs the trade agents.
- 🟧 **Risk gate** — nothing becomes a "trade" without passing it.

Freshness is enforced at the data layer: if a snapshot is missing/stale, the pipeline stops
and refreshes before any agent analyses. See `docs/PIPELINE.md` for stage rules.