# Bedrock Enhanced Client

> **⚠️ Experimental:** This is an experimental client for exploration and demonstration purposes. It is not production-ready and should not be used in production workloads. APIs, behavior, and configuration may change without notice.

A high-level client for Amazon Bedrock Runtime that provides stateful conversation management, context window strategies, automatic prompt caching, cost estimation, and enhanced retry policies.

## Problem

The Bedrock Converse API is stateless — developers must manually resend full conversation history every turn, leading to unbounded cost growth. Models have finite context windows, prompt caching is not enabled by default, and each model family has different caching mechanisms and constraints. Standard SDK retry strategies are insufficient for Bedrock's token-per-minute limits and concurrent request caps.

## Solution

The Bedrock Enhanced Client wraps `BedrockRuntimeClient` and manages the full lifecycle of multi-turn conversations behind a model-agnostic interface. Three lines of code replace 50+ lines of manual state management.

```java
// Token mode (preferred) — TARGET and MAX in tokens
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .contextWindowConfig(c -> c.contextStrategy(ContextStrategy.COST_OPTIMIZED_TRIMMING)
                              .targetRecentTokens(10000)   // T = 10K tokens retained after trim
                              .maxRecentTokens(20000)      // M = 20K tokens triggers hard trim
                              .expectedTotalTurns(100))
    .build();

ChatSession session = client.createSession("us.anthropic.claude-sonnet-4-5-20250929-v1:0");
ChatResponse response = session.converse("Tell me about Horcruxes");
ChatResponse followUp = session.converse("How were they destroyed?");

CostEstimate cost = session.costEstimate();
System.out.println("Total cost: $" + cost.totalCost());
System.out.println("Cache savings: $" + cost.cacheReadSavings());
```

## Modules

| Module | Description |
|--------|-------------|
| `bedrock-enhanced` | The library — context window management, caching, cost tracking, retries |
| `bedrock-enhanced-demo` | Spring Boot web app comparing strategies side-by-side |

## Context Window Strategies

| Strategy | Behavior | Caching | Summarization |
|----------|----------|---------|---------------|
| `SLIDING_WINDOW` | Drops oldest pairs when Cmax is hit, trims to Cmin | No | No |
| `SUMMARIZE` | Summarizes all older messages (including prior summaries) into one, keeps last C messages | No | Yes |
| `COST_OPTIMIZED_TRIMMING` | Bulk trim at cost threshold, freeze remaining as cached prefix | Yes (auto) | No |
| `COST_OPTIMIZED_SUMMARIZE` | Summarize at cost threshold, freeze summary as cached prefix | Yes (auto) | Yes |

### Sliding Window (Cmin / Cmax)

- **Cmax** (`maxMessages`) — trigger point. When message count exceeds this, trimming fires.
- **Cmin** (`minMessages`) — trim target. After trimming, this many messages are retained.
- If only Cmax is set, Cmin defaults to Cmax (classic one-pair-at-a-time behavior).
- If Cmin < Cmax, the window grows from Cmin to Cmax, then bulk trims back to Cmin.

### Cost Optimized Strategies (Strategy C — TARGET/MAX with optional Nexpected)

Cost-optimized strategies retain a configurable recent-history window and re-cache the retained window after each trim. The accumulating tail is always cached so subsequent turns benefit from cache reads.

**Two modes:**

- **TOKEN_MODE (preferred)** — TARGET and MAX are measured in tokens. Tokens drive cost, latency, and context-window usage directly, so this is the recommended production path.
- **TURN_MODE (fallback)** — TARGET and MAX are measured in turns. Used when token counting isn't available or simpler tuning is preferred.

When both are configured, **token mode is authoritative**.

**Configuration:**
- **Token mode:** `targetRecentTokens` (T) + `maxRecentTokens` (M)
- **Turn mode:** `minMessages` (T*2) + `maxMessages` (M*2)
- **R (cacheReadCostRatio)** — defaults to 0.10
- **W (cacheWriteCostRatio)** — defaults to 2.0 (1-hour TTL — Sonnet 4.5+, Opus 4.6+). Use 1.25 for 5-minute TTL.
- **expectedTotalTurns** (optional) — enables early trimming between T and M when cost-justified

**Trim decision (after each completed turn, in active unit):**

```
H = current retained recent-history size

If H >= M:
    trim to T (reason: MAX_REACHED)
Elif T < H < M and expectedTotalTurns is present:
    E = max(expectedTotalTurns - currentTurn, 0)
    threshold = (W * T) / (R * (H - T))
    If E > threshold:
        trim to T (reason: COST_BASED_EARLY_TRIM)
    Else:
        no trim
Else:
    no trim
```

**Cache placement:** A cache checkpoint is placed at the end of the active messages every turn. The entire active prompt (retained base + accumulating tail) is cached.

**Caching is enabled by default** when using cost-optimized strategies (user can explicitly disable).

### Summarize

- When context exceeds C messages, summarizes everything older than the last C messages into one summary
- Includes prior summaries in the new summarization (rolling summary)
- Result is always: `[summary] + [last C messages]`

## Retries and Rate Limiting

### Bedrock-Specific Retries

Standard SDK retries use the same backoff for all errors. The enhanced client uses separate strategies for different Bedrock failure modes:

| Exception | Base Delay | Behavior |
|-----------|-----------|----------|
| `ThrottlingException` (429) | 2 seconds | Longer backoff — respects rate limits |
| `ModelNotReadyException` | 500ms | Cold start — model warming up |
| `ModelTimeoutException` | 500ms | Transient timeout — retry usually works |
| `ServiceUnavailableException` | 500ms | Brief outage — wait and retry |

Backoff uses **full-jitter exponential**: `actualDelay = random(0, min(baseDelay × 2^attempt, maxBackoff))`. This prevents thundering herd when multiple clients are throttled simultaneously.

```java
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .retryConfig(r -> r.maxRetries(5)
                       .baseDelay(Duration.ofMillis(500))
                       .throttleBaseDelay(Duration.ofSeconds(2))
                       .maxBackoff(Duration.ofSeconds(30))
                       .retryOnModelNotReady(true))
    .build();
```

### Client-Side Rate Limiting

Proactively prevents throttling by limiting request rate before hitting the server:

```java
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .throttlingConfig(t -> t.maxRequestsPerSecond(10.0)
                           .adaptiveEnabled(true))
    .build();
```

**Adaptive mode** (enabled by default):
- On throttle (429): rate is halved immediately
- On success: rate increases by 10% of max, gradually recovering
- Creates a feedback loop that auto-tunes to your account's actual limits

**How they work together:** The rate limiter prevents most throttling. The retry handler catches cases that slip through. On each throttle event, the retry handler backs off AND the rate limiter reduces its rate — so subsequent requests from all sessions are also slowed.

## Features

- **Context window management** — 4 strategies with token-based and message-based limits
- **Automatic prompt caching** — Inserts CachePointBlock markers transparently for cost-optimized strategies
- **Real-time cost tracking** — Per-session CostEstimate with differentiated cache read/write rates
- **Token budget enforcement** — Hard cap on total session spend
- **Conversation cost budget** — Optional USD budget that applies across all strategies (Default included), with WARN or ENFORCE modes
- **Model-aware throttling** — Token-per-minute tracking with adaptive backoff
- **Bedrock-specific retries** — Separate policies for throttling (429) vs transient errors (5xx)
- **Time-based message expiry** — Auto-expire messages older than a configurable duration
- **Model-agnostic API** — Hides per-model differences behind a single interface

## Conversation Cost Budget (optional)

A budget is an orthogonal layer that wraps any strategy — including `NONE` (no context management). It tracks actual spend after every response and, when the strategy supports it, can trigger an early trim before sending the next request to keep total spend within the configured ceiling.

```java
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .pricingProvider(PricingProvider.builtIn())
    .costBudgetConfig(b -> b.budget(0.50)             // USD
                            .mode(CostBudgetConfig.Mode.ENFORCE))
    .contextWindowConfig(c -> c.contextStrategy(
        ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_TRIMMING))
    .build();

ChatSession session = client.createSession(modelId);
BudgetStatus status = session.budgetStatus(); // spentSoFar / budget / remaining / mode
```

**Modes:**
- `OFF` — budget tracked but no action taken (equivalent to no budget)
- `WARN` — logs a warning when projected next-request cost would exceed the budget
- `ENFORCE` — attempts a corrective trim (when the strategy supports it). If projected cost still exceeds the remaining budget, throws `BudgetExceededException`

**Notes:**
- Without `expectedTotalTurns`, only actual spend and per-request affordability are tracked (no full-conversation forecast).
- With `expectedTotalTurns`, a forward-looking forecast is computed and used to decide between continuing, trimming early, or failing.
- Budget never silently trims below `TARGET` (T). When `Default` strategy is used with `ENFORCE` mode, the only enforcement is via `BudgetExceededException`.

## Dependencies

This project depends on the AWS SDK for Java v2 (version 2.42.20) from Maven Central:

- `software.amazon.awssdk:bedrockruntime`
- `software.amazon.awssdk:pricing`
- `software.amazon.awssdk:sdk-core`
- `software.amazon.awssdk:annotations`
- `software.amazon.awssdk:utils`
- `software.amazon.awssdk:regions`

## Building

```bash
# Build and install everything (required before running demo)
mvn clean install -DskipTests

# Run the demo
mvn spring-boot:run -pl bedrock-enhanced-demo
```

**Important:** Always run `mvn install` before `spring-boot:run` to ensure the demo uses the latest library jar.

## Running the Demo App

The demo app compares context window management strategies side-by-side in real time. It's a great way to understand how the library works before integrating it.

### Prerequisites

- Java 8+ installed
- Maven 3.6+ installed
- AWS credentials configured (via environment variables, `~/.aws/credentials`, or IAM role)
- Bedrock model access enabled in your AWS account for at least one of:
  - `us.anthropic.claude-sonnet-4-5-20250929-v1:0` (Claude Sonnet 4.5)
  - `us.anthropic.claude-sonnet-4-20250514-v1:0` (Claude Sonnet 4)
  - `us.anthropic.claude-haiku-4-5-20251001-v1:0` (Claude Haiku 4.5)

### Steps

```bash
# 1. Clone the repo
git clone <repo-url>
cd bedrock-enhanced-client

# 2. Build and install the library + demo
mvn clean install -DskipTests

# 3. (Optional) Set your AWS region if not us-east-1
export AWS_REGION=us-east-1

# 4. Start the demo server
mvn spring-boot:run -pl bedrock-enhanced-demo
```

### Using the Demo

Open http://localhost:8080 in your browser. You'll see columns — one per strategy:

| Default Column | Strategy Columns |
|----------------|-----------------|
| No context management — full history every turn, cost grows unbounded | Each configured strategy shows its own response, cost, and metrics |

**Manual mode:** Type a message at the bottom and hit Send. The same message runs through all strategies in parallel.

**Auto Demo mode:** Click the **▶ Auto Demo** button to run a pre-built 20-turn Harry Potter conversation. Watch the savings bar as cost divergence grows with each turn.

**Configure strategies:** Click **⚙ Configure** in the header to:
- Add/remove strategy columns
- Choose strategy type (Sliding Window, Summarize, Cost Optimized Trimming, Cost Optimized Summarization)
- Set parameters (Max Tokens, C, Cmax, Caching)
- Set an optional **Cost Budget** per strategy: pick `Off`, `Warn`, or `Enforce` from the Budget Mode dropdown. Selecting Warn or Enforce reveals a Budget USD field. Each strategy is opt-in — others stay at Off.
- Only relevant parameters are shown per strategy type

**Per-strategy budget bar:** When a strategy has a budget configured, a colored progress bar appears at the top of that column showing spend vs budget and percentage used. The bar gradients green to amber to red as you approach 100% and turns solid red on `BudgetExceededException`. Strategies without a budget show no bar.

**Per-strategy errors:** Each column reports its own outcome. If one strategy throws `BudgetExceededException`, the other columns still complete normally and only the failing column shows the error message.

**Switch models:** Use the dropdown in the header to compare behavior across Claude Sonnet 4.5, Sonnet 4, and Haiku 4.5.

**Metrics dashboard:** Open http://localhost:8080/metrics.html for per-turn charts and raw data table (auto-refreshes every 3 seconds).

**Presentation slides:** Open http://localhost:8080/slides.html for a slide deck explaining the library's design and motivation (arrow keys to navigate).

### Configuration

Edit `bedrock-enhanced-demo/src/main/resources/application.properties`:

```properties
# AWS region (must have Bedrock access)
aws.region=us-east-1

# Server port
server.port=8080
```

## Requirements

- Java 8+ (target: Java 8)
- Maven 3.6+
- AWS credentials configured for Bedrock access
- Bedrock model access enabled in your AWS account

## Project Structure

```
bedrock-enhanced-client/
├── pom.xml                          # Parent POM (AWS SDK BOM 2.42.20)
├── bedrock-enhanced/                # The library
│   ├── pom.xml
│   └── src/main/java/.../bedrock/
│       ├── BedrockEnhancedClient.java       # Main entry point
│       ├── ChatSession.java                 # Stateful session interface
│       ├── ChatResponse.java                # Response wrapper
│       ├── ContextWindowConfig.java         # Strategy + limits config
│       ├── PromptCachingConfig.java         # Cache checkpoint config
│       ├── CostEstimate.java                # Running cost data
│       ├── PricingProvider.java             # Pricing interface
│       ├── CreateSessionRequest.java        # Session creation params
│       ├── TokenUsageSummary.java           # Cumulative token stats
│       ├── BedrockRetryConfig.java          # Retry configuration
│       ├── ThrottlingConfig.java            # Rate limiting config
│       └── internal/
│           ├── DefaultBedrockEnhancedClient.java
│           ├── DefaultChatSession.java
│           ├── ContextWindowManager.java
│           ├── CostOptimizedContextManager.java
│           ├── ConversationSummarizer.java
│           ├── BedrockRetryHandler.java
│           ├── TokenBucketRateLimiter.java
│           ├── StaticPricingProvider.java
│           └── ApiPricingProvider.java
├── bedrock-enhanced-demo/           # Demo web app
│   ├── pom.xml
│   └── src/
│       ├── main/java/.../demo/
│       │   ├── DemoApplication.java
│       │   ├── BedrockConfig.java
│       │   ├── SessionManager.java
│       │   ├── ChatController.java
│       │   └── StrategyConfig.java
│       └── main/resources/
│           ├── application.properties
│           └── static/
│               ├── index.html       # Main demo UI
│               ├── slides.html      # Presentation deck
│               └── metrics.html     # Metrics dashboard
├── README.md
└── VOICEOVER_SCRIPT.md              # Presentation voiceover script
```
