# Bedrock Enhanced Client

A high-level client for Amazon Bedrock Runtime that provides stateful conversation management, context window strategies, automatic prompt caching, cost estimation, and enhanced retry policies.

## Problem

The Bedrock Converse API is stateless — developers must manually resend full conversation history every turn, leading to unbounded cost growth. Models have finite context windows, prompt caching is not enabled by default, and each model family has different caching mechanisms and constraints. Standard SDK retry strategies are insufficient for Bedrock's token-per-minute limits and concurrent request caps.

## Solution

The Bedrock Enhanced Client wraps `BedrockRuntimeClient` and manages the full lifecycle of multi-turn conversations behind a model-agnostic interface. Three lines of code replace 50+ lines of manual state management.

```java
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .contextWindowConfig(c -> c.contextStrategy(ContextStrategy.COST_OPTIMIZED_TRIMMING)
                              .maxMessages(20))
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

### Cost Optimized Strategies

- Uses a threshold formula: `T = 2 × S_eff × (α − β)` to decide when to trim
- After trim, remaining messages are frozen as a cacheable prefix
- Subsequent turns pay ~10% of normal input rate for cached prefix tokens
- **Caching is enabled by default** when using cost-optimized strategies (user can explicitly disable)
- C is configured via `maxMessages` (coherenceFloor = maxMessages / 2 internally)

### Summarize

- When context exceeds C messages, summarizes everything older than the last C messages into one summary
- Includes prior summaries in the new summarization (rolling summary)
- Result is always: `[summary] + [last C messages]`

## Features

- **Context window management** — 4 strategies with token-based and message-based limits
- **Automatic prompt caching** — Inserts CachePointBlock markers transparently for cost-optimized strategies
- **Real-time cost tracking** — Per-session CostEstimate with differentiated cache read/write rates
- **Token budget enforcement** — Hard cap on total session spend
- **Model-aware throttling** — Token-per-minute tracking with adaptive backoff
- **Bedrock-specific retries** — Separate policies for throttling (429) vs transient errors (5xx)
- **Time-based message expiry** — Auto-expire messages older than a configurable duration
- **Model-agnostic API** — Hides per-model differences behind a single interface

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
- Only relevant parameters are shown per strategy type

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
