# Bedrock Enhanced Client — Problem Statement & Proposed Solutions

## Executive Summary

Developers building multi-turn conversational AI applications on Amazon Bedrock using the AWS SDK for Java v2 face significant complexity that the low-level Converse API does not address. The Bedrock Enhanced Client eliminates this undifferentiated heavy lifting by providing a high-level, stateful conversation abstraction with built-in context management, cost controls, and performance optimizations.

---

## Problems

### 1. Conversation State Management is Manual and Error-Prone

The Bedrock Converse API is stateless. Every request must include the entire conversation history as a list of messages. Developers must:

- Maintain a growing list of user and assistant messages across turns
- Serialize and deserialize message objects correctly
- Ensure alternating user/assistant message ordering
- Handle the response and append it back to the history before the next turn

This results in 50-100 lines of boilerplate per application, duplicated across every team building on Bedrock. Mistakes lead to broken conversations, lost context, or malformed requests.

### 2. Context Window Limits Cause Silent Failures

Every foundation model has a maximum context window (e.g., 200K tokens for Claude). As conversations grow, the resent history eventually exceeds this limit, causing API errors. Developers must:

- Track how many tokens are in the current conversation
- Decide what to do when the limit is approached (truncate? summarize? fail?)
- Implement the chosen strategy correctly without breaking conversation coherence
- Handle the fact that token counting requires model-specific tokenizers they don't have access to

Most teams either ignore this until it breaks in production, or implement a naive truncation that loses important context.

### 3. No Built-In Cost Visibility or Controls

Bedrock charges per token, and multi-turn conversations amplify costs because the entire history is resent each turn. A 10-turn conversation doesn't cost 10x a single turn — it costs roughly 55x (1+2+3+...+10) due to the cumulative resending. Developers have no way to:

- Track running costs during a session
- Set spending limits per conversation
- Understand the cost impact of different context management strategies
- Differentiate between regular, cache-read, and cache-write token costs

Teams discover cost overruns after the fact through billing dashboards, not in real-time.

### 4. Prompt Caching Requires Manual Configuration

Bedrock supports prompt caching that can reduce latency by up to 85% and input costs by up to 90%. However, using it requires developers to:

- Understand the CachePointBlock API and where to place checkpoints
- Know minimum token thresholds per model (1024 for Claude, 4096 for Opus 4.5)
- Insert cache markers at stable positions in the message list
- Handle the three separate token buckets (regular, cache-read, cache-write) in billing

Most developers either don't know about prompt caching or find it too complex to implement correctly for multi-turn conversations.

### 5. Throttling and Retry Logic is Generic

The SDK's built-in retry policy treats all errors the same. Bedrock has specific throttling patterns (429 responses with model-specific rate limits) that benefit from:

- Separate backoff strategies for throttling vs transient errors
- Client-side rate limiting to proactively avoid hitting server limits
- Adaptive throttling that adjusts based on observed error rates

Without these, applications either over-retry (wasting time) or under-retry (dropping requests unnecessarily).

### 6. Long Conversations Accumulate Stale Context

In real-time applications (customer support, live coding assistants), messages from 30 minutes ago may be irrelevant. There's no built-in way to expire old context based on time, leading to:

- Wasted tokens on stale information
- Model confusion from outdated context
- Unnecessary cost from resending irrelevant history

---

## Proposed Solutions

### Solution 1: Stateful ChatSession Abstraction

A `ChatSession` object that maintains conversation state automatically:

```java
ChatSession session = client.createSession("anthropic.claude-3-haiku-20240307-v1:0");
ChatResponse response = session.converse("Hello");
ChatResponse followUp = session.converse("Tell me more");
// History is managed automatically — no manual message list
```

The session handles message ordering, history accumulation, and response integration. Developers call `converse()` and get back a response. The session is the single source of truth for conversation state.

### Solution 2: Configurable Context Window Management

Four trim strategies that automatically reduce context when limits are reached:

| Strategy | How It Works | Best For |
|----------|-------------|----------|
| Sliding Window | Drops oldest user/assistant pairs gradually | General conversations |
| Summarize | Uses the model to compress older messages into a summary | Long conversations needing semantic continuity |
| Chunked | Keeps the most recent N chunks of M messages | Batch processing, predictable boundaries |
| Drop Oldest Keep Last | Keeps only the latest message | Stateless Q&A |

Plus two overflow policies:
- **TRIM** (default): Automatically applies the chosen strategy
- **REJECT**: Throws an exception, letting the application decide

Configuration example:
```java
ContextWindowConfig.builder()
    .maxTokens(8192)
    .maxMessages(50)
    .trimStrategy(TrimStrategy.SUMMARIZE)
    .overflowPolicy(OverflowPolicy.TRIM)
    .maxAge(Duration.ofMinutes(15))  // Time-based expiry
    .build()
```

### Solution 3: Real-Time Cost Estimation with Token Budget Enforcement

Two layers of cost control:

**Token Budget** — Hard cap per session. When cumulative usage exceeds the budget, the next call throws `TokenBudgetExceededException`:

```java
ChatSession session = client.createSession(
    CreateSessionRequest.builder()
        .modelId(modelId)
        .tokenBudget(50000)  // Max 50K tokens for this conversation
        .build());
```

**Cost Estimation** — Real-time running costs with differentiated pricing for regular, cache-read, cache-write, and output tokens:

```java
CostEstimate cost = session.costEstimate();
// cost.totalCost()        → $0.003450
// cost.cacheReadSavings() → $0.000120
```

Two pricing providers:
- `PricingProvider.api()` — Live pricing from AWS Pricing API
- `PricingProvider.builtIn()` — Static table, no API calls needed

### Solution 4: Automatic Prompt Caching

Cache checkpoints are inserted automatically into conversation requests. Enabled by default with two strategies:

- **CHECKPOINT_EVERY_TURN**: Places a checkpoint after the last assistant message, making the entire conversation prefix cacheable
- **SYSTEM_PROMPT_ONLY**: Caches just the system prompt (useful for large system prompts with short conversations)

System prompt caching is also automatic — a `CachePointBlock` is appended after system prompts so the system instruction prefix is reused across turns.

No developer action required. The client handles checkpoint placement, and Bedrock handles the caching transparently.

### Solution 5: Bedrock-Specific Retry and Throttling

**Enhanced Retry**: Separate backoff configurations for throttling (429) vs transient errors (5xx). Full-jitter exponential backoff prevents thundering herd.

**Client-Side Rate Limiting**: Token-bucket rate limiter with configurable max requests per second. Optional adaptive mode adjusts the rate based on observed throttling responses.

```java
BedrockEnhancedClient.builder()
    .retryConfig(r -> r.maxRetries(5)
                       .throttlingBaseDelay(Duration.ofSeconds(2)))
    .throttlingConfig(t -> t.maxRequestsPerSecond(5.0)
                            .adaptiveEnabled(true))
    .build()
```

### Solution 6: Time-Based Message Expiry

Optional `maxAge` on `ContextWindowConfig` automatically removes messages older than a specified duration before each turn:

```java
ContextWindowConfig.builder()
    .maxAge(Duration.ofMinutes(10))
    .build()
```

Messages are expired based on when they were added to the session, independent of token or message count limits. This keeps the context fresh for real-time applications without requiring explicit cleanup.

---

## Solution Summary

| Problem | Solution | Default Behavior |
|---------|----------|-----------------|
| Manual conversation state | ChatSession abstraction | Automatic history management |
| Context window overflow | 4 trim strategies + 2 overflow policies | Sliding window trim at 4096 tokens |
| No cost visibility | CostEstimate + PricingProvider | Opt-in, token counts always available |
| No spending limits | Token budget per session | No limit (opt-in) |
| Prompt caching complexity | Automatic cache checkpoint insertion | Enabled, checkpoint every turn |
| Generic retry logic | Bedrock-specific retry + rate limiting | 3 retries, no rate limiting |
| Stale context accumulation | Time-based message expiry | Disabled (opt-in) |
