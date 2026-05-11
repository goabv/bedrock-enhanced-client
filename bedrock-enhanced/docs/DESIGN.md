# Bedrock Enhanced Client — High-Level Design

## Overview

The Bedrock Enhanced Client is a high-level wrapper around `BedrockRuntimeClient` that provides stateful conversation management for the Converse API. It lives in `services-custom/bedrock-enhanced` following the same module pattern as `dynamodb-enhanced` and `s3-transfer-manager`.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│              BedrockEnhancedClient                   │
│  (Thread-safe, creates sessions)                    │
│                                                     │
│  Config: ContextWindowConfig, ThrottlingConfig,     │
│          BedrockRetryConfig, PromptCachingConfig,   │
│          PricingProvider                            │
└──────────────────┬──────────────────────────────────┘
                   │ createSession()
                   ▼
┌─────────────────────────────────────────────────────┐
│                  ChatSession                         │
│  (Stateful, one per conversation)                   │
│                                                     │
│  ┌─────────────────┐  ┌──────────────────────┐     │
│  │ContextWindow    │  │ Token Tracking        │     │
│  │Manager          │  │ - totalInputTokens    │     │
│  │- messages[]     │  │ - totalOutputTokens   │     │
│  │- timestamps{}   │  │ - cacheRead/Write     │     │
│  │- actualTokens   │  │ - turnCount           │     │
│  │- trimStrategy   │  └──────────────────────┘     │
│  └─────────────────┘                                │
│  ┌─────────────────┐  ┌──────────────────────┐     │
│  │BedrockRetry     │  │TokenBucketRate       │     │
│  │Handler          │  │Limiter               │     │
│  └─────────────────┘  └──────────────────────┘     │
└──────────────────┬──────────────────────────────────┘
                   │ converse()
                   ▼
┌─────────────────────────────────────────────────────┐
│           BedrockRuntimeClient.converse()            │
│           (Low-level Converse API call)              │
└─────────────────────────────────────────────────────┘
```

## Package Structure

```
software.amazon.awssdk.enhanced.bedrock
├── BedrockEnhancedClient.java      # Main entry point (interface)
├── ChatSession.java                # Session interface
├── ChatResponse.java               # Response wrapper
├── ContextWindowConfig.java        # Window config with strategies
├── ContextWindowExceededException  # Thrown on REJECT overflow
├── BedrockRetryConfig.java         # Retry configuration
├── ThrottlingConfig.java           # Rate limiting config
├── PromptCachingConfig.java        # Cache checkpoint config
├── CreateSessionRequest.java       # Session creation params
├── TokenUsageSummary.java          # Cumulative token stats
├── TokenBudgetExceededException    # Thrown on budget exceeded
├── CostEstimate.java               # Running cost data
├── PricingProvider.java            # Pricing interface
└── internal/
    ├── DefaultBedrockEnhancedClient.java
    ├── DefaultChatSession.java
    ├── ContextWindowManager.java
    ├── ConversationSummarizer.java
    ├── BedrockRetryHandler.java
    ├── TokenBucketRateLimiter.java
    ├── ApiPricingProvider.java
    └── StaticPricingProvider.java
```

## Core Flow: converse()

```
1. User calls session.converse("message")
2. User message → Message object → addMessage() to ContextWindowManager
3. ContextWindowManager expires old messages (if maxAge set)
4. ContextWindowManager checks REJECT policy (if configured)
5. Message added to history
6. doConverse() called:
   a. Check token budget
   b. Acquire rate limiter permit
   c. Get messages from ContextWindowManager
   d. Inject CachePointBlock markers (if prompt caching enabled)
   e. Build ConverseRequest with messages, system prompts, inference config
   f. Execute via BedrockRetryHandler → BedrockRuntimeClient.converse()
   g. Add assistant response to ContextWindowManager
   h. Update token counts from Bedrock response (input + cacheRead + cacheWrite + output)
   i. ContextWindowManager.updateTokenCountAndTrim() — trim if over cap
   j. Update cumulative totals and cost tracking
7. Return ChatResponse
```

## Context Window Management

### Token Counting

Token counts are based entirely on actual values from Bedrock API responses — no heuristic estimation. The context window size = `inputTokens + cacheReadInputTokens + cacheWriteInputTokens` from the latest response (what was sent). For trimming decisions, output tokens are added since the response is already in history for the next turn.

### Trim Strategies

| Strategy | Behavior | Use Case |
|----------|----------|----------|
| SLIDING_WINDOW | Removes oldest user/assistant pairs until under limit | General purpose, gradual context loss |
| DROP_OLDEST_KEEP_LAST | Keeps only the latest message | Stateless Q&A, aggressive reset |
| SUMMARIZE | Calls model to summarize older messages into one | Long conversations needing semantic continuity |
| CHUNKED | Keeps most recent N chunks of M messages | Predictable boundaries, batch processing |

### Overflow Policies

| Policy | Behavior |
|--------|----------|
| TRIM | Automatically applies trim strategy (default) |
| REJECT | Throws ContextWindowExceededException, caller decides |

### Time-Based Expiry

Optional `maxAge` (Duration) removes messages older than the threshold before each turn, independent of token/message limits. Useful for fast-paced conversations where old context becomes irrelevant.

## Prompt Caching

Automatically inserts `CachePointBlock` markers into requests to enable Bedrock's prompt caching:

- **CHECKPOINT_EVERY_TURN** (default): Appends cache checkpoint to the last assistant message's content blocks. The conversation prefix up to that point becomes cacheable.
- **SYSTEM_PROMPT_ONLY**: Appends cache checkpoint after system prompts only.
- **System prompt caching**: Optionally appends a `SystemContentBlock.fromCachePoint()` after system prompts.

Cache checkpoints are injected into the request only, not stored in conversation history.

## Cost Estimation

`CostEstimate` tracks four token categories with differentiated pricing:

| Token Type | Rate | Description |
|-----------|------|-------------|
| Regular input | Full rate | Non-cached input tokens |
| Cache read | ~10% of input | Tokens served from cache |
| Cache write | ~125% of input | Tokens written to cache |
| Output | Output rate | Generated tokens |

Two `PricingProvider` implementations:
- `PricingProvider.api()` — Fetches live pricing from AWS Pricing API (GetProducts), caches in memory per model
- `PricingProvider.builtIn()` — Static pricing table, no API calls

Pricing is opt-in (disabled by default). When no provider is configured, `costEstimate()` returns token counts with zero costs.

## Retry and Throttling

**BedrockRetryHandler**: Full-jitter exponential backoff with separate configurations for throttling (429) vs transient errors (5xx). Configurable max retries, base delay, and max delay.

**TokenBucketRateLimiter**: Client-side rate limiting using token bucket algorithm. Configurable max requests per second with optional adaptive mode that adjusts rate based on throttling responses.

## Token Budget

Optional per-session token budget. Checked before each API call. When cumulative `totalInputTokens + totalOutputTokens >= budget`, throws `TokenBudgetExceededException` with current usage and budget values. Budget is not enforced mid-call — the current turn completes, but the next call is rejected.

## Thread Safety

- `BedrockEnhancedClient`: Thread-safe, immutable after construction
- `ChatSession`: NOT thread-safe, one per conversation thread
- `ContextWindowManager`: NOT thread-safe, owned by session
- `PricingProvider` implementations: Thread-safe (ConcurrentHashMap cache)

## Dependencies

| Dependency | Required | Purpose |
|-----------|----------|---------|
| bedrockruntime | Yes | Converse API calls |
| sdk-core | Yes | Base SDK types |
| annotations | Yes | @SdkPublicApi, @SdkInternalApi |
| utils | Yes | Logger, ToString, Validate |
| regions | Yes | Region for client creation |
| pricing | Yes | AWS Pricing API for cost estimation |
| auth | Test only | Credentials for integration tests |
