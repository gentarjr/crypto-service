# 🤖 Raya-BOT — Crypto Trading Bot

Automated cryptocurrency trading bot built with **Java Spring Boot**, trading live on **Binance Spot** market. Implements adaptive dual-strategy trading with production-grade risk management, real-time WebSocket price monitoring, and Telegram notifications.

---

## 📋 Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
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

---

## Overview

Raya-BOT is a fully automated spot trading bot that:

- Trades **BNB/USDT** on Binance (configurable to any pair)
- Uses **15-minute candles** as the primary timeframe
- Automatically selects strategy based on **market regime** (trending vs ranging)
- Protects positions with **OCO orders** placed directly on Binance exchange
- Monitors positions in **real-time** via WebSocket (not just on candle close)
- Sends all trade notifications via **Telegram**
- Exposes a **mobile-first dashboard** for monitoring

The bot is designed to be hands-off after deployment — it evaluates signals every candle, manages open positions autonomously, and enforces all risk rules automatically.

---

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                     CandleScheduler                      │
│  (runs every 60s — detects new closed candle)            │
└───────────────┬─────────────────────────────────────────┘
                │ new closed candle
                ▼
┌─────────────────────────────────────────────────────────┐
│                    IndicatorService                       │
│  Calculates: EMA9/21, RSI, ATR, BB, ADX, Volume ratio   │
└───────────────┬─────────────────────────────────────────┘
                │ GetIndicatorResponse (snapshot)
                ▼
┌─────────────────────────────────────────────────────────┐
│                  AdaptiveSignalService                    │
│                                                          │
│  ADX >= 25 → TRENDING   → EmaSignalService               │
│  ADX < 20  → RANGING    → BbSignalService                │
│  ADX 20-25 → TRANSITION → NO TRADE                       │
└───────────────┬─────────────────────────────────────────┘
                │ Signal (BUY / HOLD)
                ▼
┌─────────────────────────────────────────────────────────┐
│                  OrderExecutorService                     │
│  - Execute market BUY order                              │
│  - Place OCO (TP + SL) on exchange                       │
│  - Monitor position every candle + realtime              │
│  - Manage trailing stop loss                             │
│  - Close position on SL/TP/timeout hit                   │
└───────────────┬─────────────────────────────────────────┘
                │ real-time price
                ▼
┌─────────────────────────────────────────────────────────┐
│                BinanceWebSocketService                    │
│  - Streams live price every second                       │
│  - Auto-reconnect with exponential backoff               │
│  - Health monitoring with Telegram alert                  │
└─────────────────────────────────────────────────────────┘
```

### Key Components

| Component | Responsibility |
|---|---|
| `CandleScheduler` | Fetches candles every 60s, detects closed candles, orchestrates the pipeline |
| `CandleCache` | Thread-safe in-memory candle storage with read-write lock |
| `IndicatorService` | Calculates all technical indicators from cached candles |
| `AdaptiveSignalService` | Routes to correct strategy based on ADX regime |
| `EmaSignalService` | EMA Crossover strategy with scoring engine |
| `BbSignalService` | Bollinger Band mean reversion strategy with scoring engine |
| `OrderExecutorService` | Executes orders, tracks live positions, manages OCO & trailing SL |
| `BinanceWebSocketService` | Real-time price stream with auto-reconnect |
| `WebSocketHealthMonitor` | Monitors WebSocket health, alerts via Telegram if feed dies |
| `TrailingStopHelper` | ATR-based trailing stop logic (activates after 1R profit) |
| `SentimentService` | Social sentiment scoring from LunarCrush + Fear & Greed index |
| `HealthCheckService` | Validates Binance API reachability, credentials, balance, clock skew |
| `TelegramNotificationService` | Sends all bot notifications via Telegram Bot API |

---

## Trading Strategies

### Strategy Selection — ADX Regime Filter

The bot automatically selects the appropriate strategy based on **ADX (Average Directional Index)**:

```
ADX >= 25          → TRENDING      → EMA Crossover Strategy
ADX 20–25          → TRANSITION    → NO TRADE (wait for clear regime)
ADX < 20           → RANGING       → BB Mean Reversion Strategy
ADX >= 40          → STRONG TREND  → EMA Crossover Strategy (full size)
```

This prevents applying a trend-following strategy in a ranging market and vice versa.

---

### Strategy 1: EMA Crossover (Trending Market)

**When active:** ADX >= 25 (trending regime)

**Core logic:**

Enters long when price is in an uptrend confirmed by multiple filters. Uses a mandatory gate + scoring system — a trade only fires when enough conditions align.

**Mandatory gates (any fail = NO TRADE):**

| Gate | Condition |
|---|---|
| ADX regime | ADX > 25 (trending) |
| Trend direction | +DI > -DI (upward momentum) |
| EMA alignment | EMA9 > EMA21 (uptrend) |
| 4H macro filter | Price above 4H EMA50 (no buying in macro downtrend) |
| ATR circuit breaker | Volatility zone must not be EXTREME |
| Pullback gate | Price must not be too extended from EMA9 (>1.5% = blocked) |

**Scoring system (adds confidence):**

| Signal | Points |
|---|---|
| Golden Cross (EMA9 crosses above EMA21) | +30 |
| EMA uptrend continuation | +10 |
| Volume surge ≥ 1.5x average | +20 |
| Volume ok ≥ 1.0x average | +10 |
| RSI in healthy zone (40–60) | +15 |
| Price near EMA9 pullback (<0.3%) | +15 |
| 4H trend BULLISH | +20 |
| Social sentiment (EMA-aligned) | up to +15 |

Minimum score to trade: **65/100** (normal size 75%), **85/100** (full size 100%)

**Exit:**
- Stop Loss: `entry - (ATR × 1.5)`
- Take Profit: `entry + (ATR × 2.0)` — then trailing SL activates
- Trailing SL activates when profit reaches 1R (distance to initial SL)
- OCO order placed on Binance exchange for exchange-level protection

---

### Strategy 2: BB Mean Reversion (Ranging Market)

**When active:** ADX < 20 (ranging regime)

**Core logic:**

Enters long when price touches or breaks below the lower Bollinger Band, expecting a reversion to the mean (middle band).

**Mandatory gates:**

| Gate | Condition |
|---|---|
| ADX regime | ADX < 20 (ranging) |
| ATR circuit breaker | Volatility not EXTREME |
| Candle confirmation | Last candle must be bullish (reversal confirmation) |
| Falling knife protection | BB %B must not be below -0.1 (price collapsing) |
| Volume minimum | Volume ratio ≥ 0.7x (avoid fake reversal on no volume) |
| Sentiment block | No extreme greed (would invalidate mean reversion setup) |

**Scoring system:**

| Signal | Points |
|---|---|
| Price at/below lower BB | +35 |
| Price within 0.5% above lower BB | +10 |
| Bullish candle | +15 |
| Volume surge ≥ 1.5x | +15 |
| Low/normal ATR | +10 |
| BB %B below 0 | +10 |
| Fearful sentiment (ideal for reversal) | up to +15 |

Minimum score to trade: **60/100** (normal), **80/100** (full size)

**Exit:**
- Stop Loss: `BB lower - (ATR × 0.5)`
- Take Profit: `BB middle + (ATR × 1.5)`
- Fixed TP (no trailing — mean reversion is a bounded move)
- OCO order placed on Binance exchange

---

## Signal Engine

### How Signals Are Built

Every candle close triggers a full pipeline:

```
1. Fetch latest candles from Binance
2. Detect if a new candle has closed (CandleUpdateResult)
3. Calculate all indicators (IndicatorService)
4. Fetch 4H candles for macro filter
5. Evaluate strategy via AdaptiveSignalService
6. If BUY signal → check all risk gates → execute
```

### Signal Deduplication

`AdaptiveSignalService` tracks the last signal action. Duplicate signals (same action, same strategy in consecutive candles) are filtered to prevent notification spam.

### Multi-Timeframe Analysis (MTA)

- **Primary timeframe:** M15 (signal generation)
- **Macro timeframe:** 4H (trend filter)
  - If price is below 4H EMA50 → EMA strategy is blocked regardless of M15 signal
  - 4H data is cached for 30 minutes to avoid excess API calls

---

## Risk Management

### Position Sizing

Position size is calculated from **risk per trade**, not a fixed dollar amount:

```
risk_amount = capital × risk_per_trade_percent (default: 1.0%)
sl_distance_pct = (entry - stop_loss) / entry
position_size = risk_amount / sl_distance_pct
```

Position size is then capped at `max_position_percent` (75%) of available balance.

### Daily Risk Controls

| Control | Default | Behavior |
|---|---|---|
| Max daily loss | 5.0% | Bot halts for the day if daily PnL drops below this |
| Max consecutive losses | 10 | Bot halts if 10 losses in a row |
| Cooldown after loss (EMA) | 60 minutes | No new EMA trades for 60 min after a loss |
| Cooldown after loss (BB) | 30 minutes | No new BB trades for 30 min after a loss |
| Max slippage | 0.8% | Trade skipped if fill price deviates too much |
| Position timeout | 2 hours | Force close if no progress AND no trailing active |

Daily limits reset at midnight UTC.

### OCO Order Protection

After every successful BUY, an OCO (One-Cancels-the-Other) order is placed directly on Binance:

```
OCO = simultaneous TP limit order + SL stop-limit order
If either hits → the other is automatically cancelled by exchange
```

This means the position is protected **even if the bot crashes or the VPS goes down**.

OCO is updated automatically when trailing SL moves by ≥ $0.50.

### Trailing Stop Loss

For EMA strategy only (trending positions):

1. Initially: fixed SL at `entry - (ATR × 1.5)`
2. When profit reaches **1R** (profit = initial SL distance): trailing activates
3. SL moves to breakeven (entry price)
4. Each subsequent candle: SL = `highest_price - (ATR × 1.5)`
5. SL only moves up — never down (ratchet mechanism)

---

## Indicators

All indicators are calculated from the M15 candle cache on every closed candle.

| Indicator | Period | Usage |
|---|---|---|
| EMA Fast | 9 | Primary trend signal |
| EMA Slow | 21 | Primary trend signal |
| EMA 4H | 50 | Macro trend filter |
| RSI | 14 | Momentum filter, overbought/oversold guard |
| ATR | 14 | SL/TP sizing, volatility zone classification |
| Bollinger Bands | 20, 2σ | BB strategy entry, %B calculation |
| ADX | 14 | Market regime detection (trending/ranging) |
| +DI / -DI | 14 | Trend direction confirmation |
| Volume MA | 20 | Volume ratio calculation |

### Volatility Zones (ATR-based)

| Zone | ATR % | Bot Behavior |
|---|---|---|
| LOW | < 0.3% | Normal trading |
| NORMAL | 0.3% – 0.8% | Normal trading |
| HIGH | 0.8% – 1.5% | Reduced position size |
| EXTREME | > 1.5% | **Hard block** — no new trades |

### Social Sentiment

Integrates with **LunarCrush API** + **Fear & Greed Index**:

- EMA strategy: likes bullish sentiment (+bonus), blocks extreme fear
- BB strategy: likes fearful sentiment (+bonus), blocks extreme greed
- Sentiment score cached for 60 minutes
- Configurable weight (default: 15 points)

---

## Project Structure

```
src/main/java/com/bot/testnet/crypto/
│
├── controller/
│   ├── LiveTradingController.java      # Live trading status & trade history
│   ├── SignalController.java           # Manual signal testing endpoints
│   ├── ConfigController.java           # Current config readout
│   └── HealthController.java           # Health check endpoints
│
├── service/
│   ├── trading/
│   │   ├── OrderExecutorService.java   # Core: live order execution & position management
│   │   └── PaperTradingService.java    # Paper trading simulator
│   │
│   ├── exchange/
│   │   ├── AdaptiveSignalService.java  # Strategy router (regime-based)
│   │   ├── EmaSignalService.java       # EMA Crossover strategy
│   │   ├── BbSignalService.java        # BB Mean Reversion strategy
│   │   ├── BinanceService.java         # Price & market data
│   │   ├── BinanceBuyService.java      # Market buy execution
│   │   ├── BinanceSellService.java     # Market sell execution
│   │   ├── BinanceOcoService.java      # OCO order management
│   │   ├── BalanceService.java         # Balance fetching
│   │   ├── CandleService.java          # Candle fetching from Binance
│   │   └── CandleCache.java            # Thread-safe candle storage
│   │
│   ├── indicator/
│   │   ├── IndicatorService.java       # Calculates all indicators
│   │   ├── MultiTimeframeService.java  # 4H EMA50 analysis
│   │   ├── SentimentService.java       # Social sentiment scoring
│   │   └── CandlePatternHelper.java    # Candle pattern detection
│   │
│   ├── risk/
│   │   ├── TrailingStopHelper.java     # ATR trailing SL logic
│   │   ├── TrailingStopService.java    # Trailing SL for paper trading
│   │   └── TradingHoursService.java    # Trading hours gate
│   │
│   ├── scheduler/
│   │   ├── CandleScheduler.java        # Main bot loop (every 60s)
│   │   └── DailySummaryScheduler.java  # Daily PnL summary at 23:59
│   │
│   ├── websocket/
│   │   ├── BinanceWebSocketService.java # Real-time price stream
│   │   ├── PriceCache.java              # Latest price storage
│   │   └── PriceMonitorService.java     # Triggers realtime SL/TP check
│   │
│   ├── health/
│   │   ├── HealthCheckService.java      # API + balance + clock validation
│   │   └── WebSocketHealthMonitor.java  # WebSocket feed health monitoring
│   │
│   └── TelegramNotificationService.java # Telegram Bot notifications
│
├── model/
│   ├── dto/
│   │   ├── LivePosition.java           # Real Binance position state
│   │   ├── VirtualPosition.java        # Paper trading position state
│   │   ├── Signal.java                 # Signal with action + filters
│   │   ├── SignalFilter.java           # Individual filter result (pass/fail)
│   │   ├── Candle.java                 # OHLCV candle data
│   │   ├── DailyStats.java             # Daily PnL statistics
│   │   └── TradeRecord.java            # Closed trade record
│   │
│   ├── request/                        # Request DTOs
│   └── response/                       # Response DTOs
│
└── repository/
    └── TradeHistoryRepository.java     # H2 trade history persistence
```

---

## Configuration

All configuration lives in `application-prod.yaml`. Key parameters:

### Trading Pair

```yaml
trading:
  pair:
    base: BNB       # base currency
    quote: USDT     # quote currency
```

### Indicators

```yaml
trading:
  indicators:
    ema-fast-period: 9
    ema-slow-period: 21
    atr-period: 14
    bb-period: 20
    bb-std-dev: 2.0
    adx-period: 14
    adx-ranging-threshold: 20      # ADX below this = ranging
    adx-trending-threshold: 25     # ADX above this = trending
    adx-strong-trend-threshold: 40 # ADX above this = strong trend
```

### Strategy Thresholds

```yaml
trading:
  strategy:
    ema:
      buy-score-threshold: 65        # minimum score to open EMA trade
      strong-buy-score-threshold: 85 # score for full position size
    bb:
      buy-score-threshold: 60
      strong-buy-score-threshold: 80
```

### Risk Parameters

```yaml
trading:
  risk:
    risk-per-trade-percent: 1.0      # % of capital risked per trade
    max-daily-loss-percent: 5.0      # daily drawdown limit
    max-consecutive-losses: 10       # consecutive loss limit
    cooldown-minutes: 60             # post-loss cooldown (EMA)
    bb-cooldown-minutes: 30          # post-loss cooldown (BB)
    sl-atr-multiplier: 1.5           # SL = entry - (ATR × this)
    tp-atr-multiplier: 2.0           # TP = entry + (ATR × this)
    trailing-atr-multiplier: 1.5     # trailing SL distance
    max-position-percent: 75.0       # max % of balance per trade
    max-slippage-percent: 0.8        # max allowed slippage
    timeout-hours: 2                 # force close after X hours if stagnant
```

---

## API Endpoints

### Status & Monitoring

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/live/status` | Current bot status, open position, cooldown state |
| GET | `/api/live/trades` | Paginated trade history |
| GET | `/api/live/daily-stats` | Today's PnL, win/loss count |
| GET | `/api/test/health` | Quick health check (uptime, capital, halt status) |
| GET | `/api/test/bot-health` | Full health check (API, balance, clock skew) |
| GET | `/api/test/bot-health/refresh` | Force refresh health check |

### Signals & Indicators

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/signal/adaptive` | Current adaptive signal with regime info |
| GET | `/api/signal/ema` | EMA strategy signal with filter breakdown |
| GET | `/api/signal/bb` | BB strategy signal with filter breakdown |
| GET | `/api/config` | Current bot configuration |
| GET | `/api/trading-hours` | Trading hours status |

### Balance

| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/balance` | Current USDT, BNB, and other balances |

---

## Dashboard

A mobile-first trading dashboard is served at `/` (root URL).

**Dashboard tabs:**

- **Home** — Live position card, current price, unrealized PnL, trailing SL status
- **Trades** — Trade history with PnL per trade, strategy used, close reason
- **Stats** — Win rate, expectancy, daily/monthly ROI projection, equity chart
- **Settings** — Bot status, risk config, strategy filter grid, indicator values

Dashboard auto-refreshes every 30 seconds. No login required (serve behind VPN or firewall in production).

---

## Deployment

### Prerequisites

- Java 17+
- VPS with minimum 1GB RAM (Singapore region recommended for low latency to Binance)
- Binance account with API key (Spot Trading permission only — no Withdrawal permission)

### Build

```bash
./mvnw clean package -DskipTests
```

### Run (Production)

```bash
java -jar target/crypto-*.jar \
  --spring.profiles.active=prod
```

### Systemd Service (recommended for auto-restart)

Create `/etc/systemd/system/cryptobot.service`:

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
├── crypto.jar              # application JAR
├── .env                    # environment variables (never commit this)
├── static/
│   └── index.html          # dashboard
├── data/
│   └── tradedb.mv.db       # H2 database (trade history)
└── logs/
    └── crypto-bot.log      # rolling log files
```

---

## Environment Variables

Create `/opt/cryptobot/.env` on the VPS:

```env
BINANCE_API_KEY=your_binance_api_key
BINANCE_SECRET_KEY=your_binance_secret_key
BINANCE_TESTNET=false
TELEGRAM_BOT_TOKEN=your_telegram_bot_token
TELEGRAM_CHAT_ID=your_telegram_chat_id
LUNARCRUSH_API_KEY=your_lunarcrush_api_key
```

> **Security note:** API key must have **Spot & Margin Trading** permission only. Never enable Withdrawal permission on a bot API key.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Framework | Java 17, Spring Boot 3 |
| Exchange API | XChange library (Binance), Binance REST API (OCO) |
| Real-time data | Java-WebSocket (Binance WebSocket stream) |
| Database | H2 (file-based, persistent) |
| Notifications | Telegram Bot API |
| Sentiment | LunarCrush API, Fear & Greed Index |
| Dashboard | Vanilla HTML/CSS/JS + Chart.js |
| Build | Maven |
| Deployment | VPS + systemd |

---

## Important Notes

1. **This bot trades real money.** Test thoroughly on testnet before enabling live trading (`BINANCE_TESTNET=false`).
2. **Past performance does not guarantee future results.** All trading involves risk of loss.
3. **Monitor the Telegram channel** regularly, especially during the first weeks of live trading.
4. **Never share your `.env` file** or commit API keys to version control.
5. **OCO orders protect the position** even if the bot crashes — but always verify manually after any unexpected downtime.

---

## License

Private — all rights reserved.
