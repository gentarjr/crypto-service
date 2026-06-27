# 🤖 Raya-BOT — Multi-Pair Crypto Trading Bot

Automated cryptocurrency trading bot built with **Java Spring Boot**, trading live on **Binance Spot**. Runs **two independent pairs in parallel** — BNB/USDT and ETH/USDC — each with its own full pipeline: candle cache, indicators, signal engine, order executor, and WebSocket price feed. Implements adaptive dual-strategy trading (EMA trend-following / BB mean-reversion) with production-grade risk management and Telegram notifications.

---

## 📋 Table of Contents

- [Overview](#overview)
- [Multi-Pair Architecture](#multi-pair-architecture)
- [Trading Strategies](#trading-strategies)
- [Signal Engine](#signal-engine)
- [Risk Management](#risk-management)
- [Indicators](#indicators)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [API Endpoints](#api-endpoints)
- [Dashboard](#dashboard)
- [Deployment](#deployment)
- [Environment Variables](#environment-variables)
- [Tech Stack](#tech-stack)
- [Known Gaps & Operational Notes](#known-gaps--operational-notes)

---

## Overview

Raya-BOT runs two fully independent trading pipelines side by side:

| Pair | Status | Quote precision | Notes |
|---|---|---|---|
| **BNB/USDT** | Original, production-proven | 2 decimal qty | Original pipeline |
| **ETH/USDC** | `add-eth` branch — duplicated pipeline | 4 decimal qty (`qty-decimal-places: 4`) | Mirrors BNB pipeline via Eth-suffixed twin classes |

Each pair:
- Uses **15-minute candles** as primary timeframe, independently cached
- Automatically selects strategy based on **market regime** (ADX trending vs ranging)
- Protects positions with **OCO orders** placed directly on Binance exchange
- Monitors positions in **real-time** via its own WebSocket connection
- Sends Telegram notifications with **explicit pair prefix** (emoji-tagged, so BNB and ETH alerts are never ambiguous)
- Exposes pair-scoped API endpoints and is rendered on a shared dashboard with a pair switcher

The two pipelines do not share mutable runtime state (candle cache, balance, position) — they share only cross-cutting infrastructure (`TradeHistoryRepository`, `TelegramNotificationService`, `WebSocketHealthMonitor`).

---

## Multi-Pair Architecture

```
┌──────────────────────────────┐      ┌──────────────────────────────┐
│         BNB/USDT Pipeline      │      │        ETH/USDC Pipeline       │
│                                │      │                                │
│  CandleScheduler               │      │  CandleSchedulerEth            │
│        ↓                      │      │        ↓                      │
│  CandleCache                   │      │  CandleCacheEth                │
│        ↓                      │      │        ↓                      │
│  IndicatorService               │      │  IndicatorServiceEth            │
│        ↓                      │      │        ↓                      │
│  MultiTimeframeService          │      │  MultiTimeframeServiceEth       │
│  SentimentService                │      │  SentimentServiceEth             │
│        ↓                      │      │        ↓                      │
│  AdaptiveSignalService          │      │  AdaptiveSignalServiceEth       │
│   ├─ EmaSignalService           │      │   ├─ EmaSignalServiceEth        │
│   └─ BbSignalService            │      │   └─ BbSignalServiceEth         │
│        ↓                      │      │        ↓                      │
│  OrderExecutorService           │      │  OrderExecutorServiceEth        │
│  PriceMonitorService            │      │  PriceMonitorServiceEth         │
│  BalanceService                  │      │  BalanceServiceEth               │
│        ↓                      │      │        ↓                      │
│  BinanceWebSocketService        │      │  BinanceWebSocketServiceEth     │
│  PriceCache                      │      │  PriceCacheEth                   │
└──────────────────────────────┘      └──────────────────────────────┘
                │                                      │
                └──────────────┬───────────────────────┘
                                ▼
                  ┌──────────────────────────┐
                  │  TradeHistoryRepository    │  (shared, pair-discriminated)
                  │  TelegramNotificationService│  (shared, pair-prefixed messages)
                  │  WebSocketHealthMonitor      │  (shared, monitors both feeds)
                  └──────────────────────────┘
```

### Architectural Pattern: Twin-Class Duplication

New pairs are added by **duplicating** the BNB pipeline classes with an `Eth` suffix rather than parametrizing a single generic pipeline. This was a deliberate tradeoff:

- ✅ Zero risk of cross-pair state leakage (no shared mutable cache/position fields)
- ✅ Each pair can be tuned/halted independently without touching the other
- ❌ Bug fixes must be applied **twice** (once per twin) — already a recurring source of drift (see [Known Gaps](#known-gaps--operational-notes))
- ❌ ~2x file count for every new pair added

### Key Components (per pair, BNB shown — Eth has 1:1 equivalents)

| Component | Responsibility |
|---|---|
| `CandleScheduler` / `CandleSchedulerEth` | Fetches candles every 60s, detects closed candles, orchestrates the pipeline |
| `CandleCache` / `CandleCacheEth` | Thread-safe in-memory candle storage with read-write lock |
| `IndicatorService` / `IndicatorServiceEth` | Calculates all technical indicators from cached candles |
| `MultiTimeframeService` / `...Eth` | 4H macro trend filter (EMA50), cached 30 min |
| `SentimentService` / `...Eth` | Social sentiment scoring (LunarCrush + Fear & Greed); ETH side currently **disabled** (`sentiment-eth.enabled: false`) |
| `AdaptiveSignalService` / `...Eth` | Routes to correct strategy based on ADX regime |
| `EmaSignalService` / `BbSignalService` (+Eth) | Strategy scoring engines |
| `OrderExecutorService` / `...Eth` | Executes orders, tracks live positions, manages OCO & trailing SL |
| `BalanceService` / `...Eth` | Pair-specific balance queries |
| `PriceMonitorService` / `...Eth` | Real-time position monitoring between candle closes |
| `BinanceWebSocketService` / `...Eth` | Real-time price stream with auto-reconnect |
| `PriceCache` / `PriceCacheEth` | Latest tick cache per pair |
| `WebSocketHealthMonitor` | **Shared** — monitors both feeds, alerts via Telegram if either dies |
| `DailySummaryScheduler` | **Shared** — aggregates both pairs into one daily Telegram summary, fires `07:00 Asia/Jakarta` (anchored to UTC midnight reset boundary) |
| `TelegramNotificationService` | **Shared** — all `sendTg()` calls are wrapped with emoji pair-prefix so BNB/ETH alerts are visually distinct |

---

## Trading Strategies

Both pairs run the **same strategy logic**, with independently configured thresholds (`trading.risk.*` for BNB, `trading.risk-eth.*` for ETH).

### Strategy Selection — ADX Regime Filter

```
ADX >= 25          → TRENDING      → EMA Crossover Strategy
ADX 20–25          → TRANSITION    → NO TRADE
ADX < 20           → RANGING       → BB Mean Reversion Strategy
ADX >= 40          → STRONG TREND  → EMA Crossover Strategy (full size)
```

### Strategy 1: EMA Crossover (Trending Market)

**Mandatory gates:** ADX > 25, +DI > -DI, EMA9 > EMA21, price above 4H EMA50, ATR not EXTREME, price not >1.5% extended from EMA9.

**Scoring (≥65 to trade, ≥85 for full size):** Golden Cross +30, EMA continuation +10, volume surge ≥1.5x +20, RSI 40–60 +15, EMA9 pullback +15, 4H bullish +20, sentiment up to +15.

**Exit:**
- SL: `entry - (ATR × sl-atr-multiplier)` — default 1.5
- TP: `entry + (ATR × tp-atr-multiplier)` — default 2.0
- Trailing SL (via `TrailingStopService`) activates at 1R profit, ratchets to `highest_price - (ATR × 1.5)`, never moves down
- OCO placed on Binance, auto-updated when trailing SL moves ≥ $0.50

### Strategy 2: BB Mean Reversion (Ranging Market)

**Mandatory gates:** ADX < 20, ATR not EXTREME, last candle bullish (reversal confirmation), BB %B ≥ -0.1 (falling-knife guard), volume ratio ≥ 0.7x, no extreme-greed sentiment block.

**Scoring (≥60 to trade, ≥80 for full size):** Price at/below lower BB +35, price within 0.5% of lower BB +10, bullish candle +15, volume surge +15, low/normal ATR +10, %B below 0 +10, fearful sentiment up to +15.

**Exit:**
- SL: `BB lower - (ATR × bb-sl-atr-multiplier)` — default **0.5** (currently under review — tight relative to fee+slippage cost on both pairs)
- TP: `BB middle + (ATR × 1.5)` (fixed, no trailing)
- Effective R:R is computed **after fee** (`FEE_RATE = 0.00075`, round-trip) and the trade is rejected if R:R < 1.2 net of fee

---

## Signal Engine

### Pipeline (runs independently per pair)

```
1. Fetch latest candles from Binance for this pair
2. Detect new closed candle (CandleUpdateResult)
3. Calculate indicators (IndicatorService / ...Eth)
4. Fetch 4H candles for macro filter (cached 30 min)
5. Evaluate strategy via AdaptiveSignalService / ...Eth
6. If BUY → check all risk gates → execute via OrderExecutorService / ...Eth
```

### Signal Deduplication

`AdaptiveSignalService` (per pair) tracks the last signal action; duplicate signals (same action, same strategy, consecutive candles) are filtered to prevent notification spam.

### Indicator Snapshot Consistency

`IndicatorService.calculate()` attaches `recentCandles`/`allCandles` to the snapshot so that **on-demand** signal endpoints (`/api/signal/*`, `/api/test/eth/signal/*`) see the same candle-pattern data as the scheduled pipeline. This was previously a gap on the on-demand path only — verify both paths after any indicator change, since they can silently diverge.

---

## Risk Management

### Position Sizing (per pair, independently configured)

```
risk_amount = capital × risk_per_trade_percent      (default 1.0%)
sl_distance_pct = (entry - stop_loss) / entry
position_size = risk_amount / sl_distance_pct
position_size = min(position_size, max_position_percent × capital)
```

R:R is recalculated **net of round-trip fee** on the actual capped position size — not on theoretical full-capital size — before a trade is allowed to fire.

### Daily / Pair-Level Risk Controls

| Control | BNB default | ETH default | Behavior |
|---|---|---|---|
| Max daily loss | 5.0% | 5.0% | Halts new trades for the day |
| Max consecutive losses | 10 | 10 | Halts pair if breached |
| Cooldown after loss (EMA) | 60 min | 60 min | No new EMA trades |
| Cooldown after loss (BB) | 30 min | 30 min | No new BB trades |
| Max slippage | 0.8% | 0.8% | Trade skipped if fill deviates too much |
| Position timeout | 2 hrs | 2 hrs | Force close if stagnant, no trailing active |
| Qty rounding | 2 decimals | **4 decimals** (`qty-decimal-places: 4`) | ETH requires finer precision per Binance `exchangeInfo` — hardcoding 2dp here silently truncates ETH qty to 0.00 and the order is rejected (HTTP 400) |
| Min quantity | exchange default | `0.003` explicit floor | Below-minimum orders rejected by Binance before they hit the book |

Each pair's daily limits reset at **UTC midnight**; `DailySummaryScheduler` fires at 07:00 WIB (Asia/Jakarta) but its internal stats window is anchored to the UTC boundary, not local time — this distinction matters because WIB ≠ UTC reset point.

### OCO Order Protection

Every BUY (either pair) places an OCO (TP limit + SL stop-limit) directly on Binance — protection survives bot crash or VPS downtime. OCO auto-updates when trailing SL moves ≥ $0.50.

### Trade-Record Integrity on Failed Closes

If a sell/close order fails, the executor still records the trade in `TradeHistory` using the best available estimated exit price, tagged with a `_MANUAL` closeReason suffix — failed closes used to return early and silently vanish from PnL/loss tracking. This applies to both `OrderExecutorService` and `OrderExecutorServiceEth`.

---

## Indicators

Identical indicator set computed independently per pair from each pair's own M15 candle cache:

| Indicator | Period | Usage |
|---|---|---|
| EMA Fast / Slow | 9 / 21 | Primary trend signal |
| EMA 4H | 50 | Macro trend filter |
| RSI | 14 | Momentum / overbought-oversold guard |
| ATR | 14 | SL/TP sizing, volatility zone |
| Bollinger Bands | 20, 2σ | BB strategy entry, %B |
| ADX / +DI / -DI | 14 | Regime detection, trend direction |
| Volume MA | 20 | Volume ratio |

### Volatility Zones (ATR-based, same thresholds both pairs)

| Zone | ATR % | Behavior |
|---|---|---|
| LOW | < 0.3% | Normal |
| NORMAL | 0.3–0.8% | Normal |
| HIGH | 0.8–1.5% | Reduced position size |
| EXTREME | > 1.5% | Hard block, no new trades |

### Social Sentiment

LunarCrush + Fear & Greed. **BNB:** active. **ETH:** present in code (`SentimentServiceEth`) but disabled via `sentiment-eth.enabled: false` and `lunarcrush-enabled: false` — ETH currently trades without the sentiment scoring bonus/block.

---

## Project Structure

```
src/main/java/com/bot/testnet/crypto/
│
├── controller/
│   ├── LiveTradingController.java        # BNB: /api/live/*
│   ├── LiveTradingControllerEth.java     # ETH: /api/live/eth/*
│   ├── DiagnosticsControllerEth.java     # ETH: /api/test/eth/* (signals, cache, health, ws status)
│   ├── SignalController.java             # BNB manual signal testing
│   ├── ConfigController.java             # Combined config readout
│   └── HealthController.java
│
├── service/
│   ├── scheduler/
│   │   ├── CandleScheduler.java / CandleSchedulerEth.java
│   │   └── DailySummaryScheduler.java    # shared, both pairs, 07:00 WIB / UTC-anchored
│   ├── exchange/
│   │   ├── CandleCache.java / CandleCacheEth.java
│   │   ├── AdaptiveSignalService.java / ...Eth.java
│   │   ├── EmaSignalService.java / EmaSignalServiceEth.java
│   │   ├── BbSignalService.java / BbSignalServiceEth.java
│   │   └── BalanceService.java / BalanceServiceEth.java
│   ├── indicator/
│   │   ├── IndicatorService.java / IndicatorServiceEth.java
│   │   ├── MultiTimeframeService.java / ...Eth.java
│   │   ├── SentimentService.java / SentimentServiceEth.java
│   │   ├── CandlePatternHelper.java       # shared
│   │   └── MarketStructureService.java    # shared
│   ├── trading/
│   │   ├── OrderExecutorService.java / OrderExecutorServiceEth.java
│   │   └── PriceMonitorService.java / PriceMonitorServiceEth.java
│   ├── websocket/
│   │   ├── BinanceWebSocketService.java / ...Eth.java
│   │   └── PriceCache.java / PriceCacheEth.java
│   ├── risk/
│   │   ├── TrailingStopService.java       # shared, strategy-gated (EMA only)
│   │   └── TradingHoursService.java       # shared
│   ├── health/
│   │   ├── HealthCheckService.java
│   │   └── WebSocketHealthMonitor.java    # shared, monitors both feeds
│   └── TelegramNotificationService.java   # shared, pair-prefixed sendTg()
│
├── model/
│   ├── dto/ (Signal, SignalFilter, Candle, VirtualPosition, LivePosition, ...)
│   └── entity/TradeHistory.java           # pair-discriminated, shared table
│
└── repository/
    └── TradeHistoryRepository.java        # shared, queries filter by pair
```

---

## Configuration

All config lives in `application-prod.yaml` — this is the **only** file that matters in production (`--spring.profiles.active=prod`). The base `application.yaml` is not used at runtime in prod and should not be treated as a source of truth; any default shown there can be stale or overridden. BNB and ETH are **fully separate config namespaces** — nothing is inherited by default; every ETH override must be set explicitly under `*-eth` keys or it silently falls back to its own `@Value` default, which may not match the BNB value.

### Trading Pairs

```yaml
trading:
  pair:
    base: BNB
    quote: USDT
  pair-eth:
    enabled: true
    base: ETH
    quote: USDC
  live-eth:
    enabled: true
```

### Risk Parameters — BNB

```yaml
trading:
  risk:
    risk-per-trade-percent: 1.0
    max-daily-loss-percent: 5.0
    max-consecutive-losses: 10
    cooldown-minutes: 60
    bb-cooldown-minutes: 30
    sl-atr-multiplier: 1.5
    tp-atr-multiplier: 2.0
    trailing-atr-multiplier: 1.5
    max-position-percent: 75.0
    max-slippage-percent: 0.8
    timeout-hours: 2
```

### Risk Parameters — ETH

```yaml
trading:
  risk-eth:
    modal: 300
    risk-per-trade-percent: 1.0
    max-daily-loss-percent: 5.0
    max-consecutive-losses: 10
    cooldown-minutes: 60
    bb-cooldown-minutes: 30
    sl-atr-multiplier: 1.5
    tp-atr-multiplier: 2.0
    trailing-atr-multiplier: 1.5
    max-position-percent: 75.0
    max-slippage-percent: 0.8
    timeout-hours: 2
    qty-decimal-places: 4      # critical — see Known Gaps
    min-quantity: 0.003
```

### Other ETH-specific blocks

```yaml
trading:
  websocket-eth:
    base-url: wss://stream.binance.com/ws
    reconnect-delay-seconds: 5
    max-reconnect-attempts: 10
    enabled: true
  mta-eth:
    enabled: true
    ema-period: 50
    cache-duration-minutes: 30
  sentiment-eth:
    enabled: false             # ETH sentiment scoring currently OFF
    lunarcrush-api-key: ${LUNARCRUSH_API_KEY}
    topic: eth
    score-weight: 15
    cache-minutes: 180
    lunarcrush-enabled: false
```

### BB Strategy SL Multiplier (both pairs, shared key — under review)

```yaml
trading:
  strategy:
    bb:
      sl-atr-multiplier: 0.8    # tight relative to fee+slippage; see live trade analysis
      tp-atr-multiplier: 1.5
```

---

## API Endpoints

### BNB

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/live/status` | Bot status, open position, cooldown |
| GET | `/api/live/trades` | Paginated trade history |
| GET | `/api/live/daily-stats` | Today's PnL, win/loss count |
| GET | `/api/signal/adaptive` `/ema` `/bb` | Signal + filter breakdown |
| GET | `/api/config` | Combined config readout |
| GET | `/api/test/health` `/bot-health` `/bot-health/refresh` | Health checks |
| GET | `/api/balance` | USDT/BNB balances |

### ETH

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/live/eth/status` | ETH bot status, open position, cooldown |
| GET | `/api/live/eth/positions` | Open ETH position |
| GET | `/api/live/eth/history` | Paginated ETH trade history |
| GET | `/api/live/eth/stats/today` | Today's ETH PnL |
| GET | `/api/test/eth/signal/adaptive` `/bb` `/ema` | ETH signal + filters |
| GET | `/api/test/eth/mta/status` | ETH 4H macro filter status |
| GET | `/api/test/eth/cache/status` `/cache/candles` | ETH candle cache introspection |
| GET | `/api/test/eth/indicators` | Current ETH indicator snapshot |
| GET | `/api/test/eth/sentiment/status` | ETH sentiment state (currently disabled) |
| GET | `/api/test/eth/health` | ETH pipeline health |
| GET | `/api/ws/eth/status` | ETH WebSocket connection status |

---

## Dashboard

Mobile-first dashboard served at `/`. Single dashboard, **shared across both pairs**, with:

- **Sticky BNB/ETH pair switcher** — persisted via `localStorage`, survives refresh
- **Home** — live position, current price, unrealized PnL, trailing SL status, for whichever pair is selected
- **Trades** — trade history with PnL, strategy, close reason
- **Stats** — win rate, expectancy, ROI projection, equity chart
- **Settings** — bot status, risk config, filter grid, indicator values
- **Dead-zone overlay** — full-screen frosted-glass overlay during `00:00–06:00 UTC` showing open positions for **both** pairs simultaneously (low-liquidity window, informational only)
- **Empty-state handling** — ETH tab renders a dedicated empty state when there are zero ETH trades yet, rather than reusing BNB's empty-state copy
- Race-condition-safe refresh via fetch tokens (pair switch mid-fetch doesn't render the wrong pair's data)
- "Bot sedang menunggu" detail panel — JS-side `buildRequirementJS()` mirrors Java's `buildRequirement()` filter logic 1:1, so the dashboard's "waiting for X" reasoning matches what the strategy actually checked, for both pairs

No login — serve behind VPN/firewall in production.

---

## Deployment

### Prerequisites

- Java 17+
- VPS, min 1GB RAM (Singapore region recommended)
- Binance API key — **Spot Trading only, no Withdrawal permission**

### Build

```bash
./mvnw clean package -DskipTests
```

### Run

```bash
java -jar target/crypto-*.jar --spring.profiles.active=prod
```

### Systemd Service

```ini
[Unit]
Description=Crypto Trading Bot
After=network.target

[Service]
Type=simple
User=ubuntu
WorkingDirectory=/opt/cryptobot
EnvironmentFile=/opt/cryptobot/.env
ExecStart=/usr/bin/java -jar /opt/cryptobot/crypto.jar --spring.profiles.active=prod
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl enable cryptobot
sudo systemctl start cryptobot
sudo systemctl status cryptobot
```

### File Structure on VPS

```
/opt/cryptobot/
├── crypto.jar
├── .env
├── static/
│   └── index.html          # shared dashboard, both pairs
├── data/
│   └── tradedb.mv.db       # H2 — TradeHistory rows discriminated by pair
└── logs/
    └── crypto-bot.log
```

---

## Environment Variables

```env
BINANCE_API_KEY=your_binance_api_key
BINANCE_SECRET_KEY=your_binance_secret_key
BINANCE_TESTNET=false
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id
LUNARCRUSH_API_KEY=your_lunarcrush_api_key
```

> Same key/secret pair is used for both BNB and ETH pipelines — there is no separate API credential per pair. Spot & Margin Trading permission only, never Withdrawal.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Java 17, Spring Boot 3 |
| Exchange API | XChange (Binance), Binance REST (OCO) |
| Real-time data | Java-WebSocket — **two independent connections**, one per pair |
| Database | H2 (file-based), shared `TradeHistory` table, pair-discriminated |
| Notifications | Telegram Bot API, pair-prefixed messages |
| Sentiment | LunarCrush API, Fear & Greed Index (BNB active, ETH disabled) |
| Dashboard | Vanilla HTML/CSS/JS + Chart.js, shared UI with pair switcher |
| Build | Maven |
| Deployment | Single VPS process + systemd, both pairs run in the same JVM |

---

## Known Gaps & Operational Notes

1. **Twin-class drift risk** — every bug fixed in a BNB class (`OrderExecutorService`, etc.) must be manually mirrored into its Eth twin. There is no shared base class enforcing parity; verify both files after any fix.
2. **BB strategy SL multiplier (0.5x ATR)** — currently tight enough that observed live BNB stop-losses (0.17%–0.32% distance) sit at or below round-trip fee cost. Under review; affects both pairs since the key is shared (`trading.strategy.bb.sl-atr-multiplier`), not split into `-eth`.
3. **ETH sentiment disabled** — `SentimentServiceEth` exists and is wired into the signal path but is config-gated off (`sentiment-eth.enabled: false`). ETH currently trades without the sentiment scoring bonus/block that BNB gets.
4. **No automated max-drawdown halt currently in code** — peak-equity-tracking circuit breaker has been discussed but is not present in the `add-eth` branch as of this writing. Daily-loss and consecutive-loss halts exist; a running max-drawdown halt does not yet.
5. **On-demand vs scheduled path parity** — historically a source of silent divergence (candle data missing from on-demand snapshots). Any change to `IndicatorService`/`IndicatorServiceEth` should be checked against both the scheduler path and the `/api/signal/*` / `/api/test/eth/signal/*` on-demand paths.
6. **This bot trades real money on both pairs.** Test config changes on testnet before flipping `BINANCE_TESTNET=false`. Monitor Telegram closely after any deploy touching shared infrastructure (`TelegramNotificationService`, `WebSocketHealthMonitor`, `DailySummaryScheduler`) since a bug there affects both pairs simultaneously.

---

## License

Private — all rights reserved.