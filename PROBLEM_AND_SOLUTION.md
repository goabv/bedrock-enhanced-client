# Bedrock Enhanced Client — Problem Statement and Solution

**Status:** Experimental prototype, seeking PMT review
**Author:** Abhinav Goyal
**Audience:** Bedrock product management and engineering leadership
**Date:** May 2026
**Reference implementation:** https://github.com/goabv/bedrock-enhanced-client

---

## TL;DR

Multi-turn conversations on Amazon Bedrock cost developers 5–50x more than they need to. Every customer building a chatbot, copilot, or agent on Bedrock is independently solving the same set of problems — context window management, prompt-cache placement, cost tracking, and retry policy — with varying degrees of correctness. The result is wasted spend, repeated bugs, broken conversations, and an inferior developer experience compared to OpenAI Assistants and Anthropic's Messages API.

We propose a higher-level Java client that wraps `BedrockRuntimeClient` and packages the right defaults for stateful conversations: a managed conversation history, four context-window strategies (including a cost-optimized one with prompt caching), automatic cache checkpoint placement, real-time cost tracking, an optional conversation cost budget, and Bedrock-specific retry/throttling.

A working prototype with a side-by-side demo exists today. Early measurements show **70–90% cost reduction** on long conversations vs the default approach, with **3 lines of configuration** instead of the 50–100 lines developers write today.

The ask: review whether this is worth productizing as an officially supported AWS abstraction (Java first, then mirrored in other SDKs).

---

## Background

### How developers use Bedrock today

The `Converse` and `ConverseStream` APIs are stateless. Every request must include the entire conversation history. That choice is correct for the API surface, but it creates a meaningful lift on every customer building a multi-turn application:

```java
// What every Bedrock Java developer writes today
List<Message> history = new ArrayList<>();
while (running) {
    Message userMsg = Message.builder()
        .role(ConversationRole.USER)
        .content(ContentBlock.fromText(userInput))
        .build();
    history.add(userMsg);

    // Trim if too long? Which messages? Token-aware or message-count?
    // Cache checkpoints? Where? Anthropic minimum 1024 tokens?
    // Retry on 429? Different backoff than 5xx? Adaptive rate limit?
    // Track cost? Different rates for cache read vs write vs regular input?
    // Budget? Stop when over $X?
    // ...

    ConverseResponse resp = client.converse(ConverseRequest.builder()
        .modelId(modelId)
        .messages(history)
        // ...
        .build());

    history.add(resp.output().message());
}
```

Each comment above is a real engineering decision the customer must make. Most teams either get them wrong, ignore them, or copy-paste a half-working solution from one project to the next.

### The problems compound on long conversations

A 200-turn conversation costs roughly **200 × the average per-turn input** because every turn re-sends the full history. With Sonnet 4.5 ($3 per million input tokens), a long support session can easily land in $5–$50 territory **per conversation** — and the cost grows quadratically with length.

Prompt caching (90% off cached input) and context trimming (drop or summarize old turns) both attack this problem. Both are non-trivial to implement correctly. Neither is on by default.

---

## Problems

### P1. Conversation state management is manual and easy to get wrong

Every customer reimplements: history accumulation, role alternation, response integration, and the surrounding error handling. We have seen the following mistakes in customer code reviews:

- Forgetting to append the assistant response, breaking subsequent turns
- Re-using the same `Message` object across turns and mutating it
- Persisting full history to a database without trimming, ballooning storage cost
- Race conditions when two requests share a session

This is undifferentiated heavy lifting that AWS could handle once.

### P2. Context window limits cause silent failures or naive truncation

Every model has a finite context window (200K for current Claude, 1M for Sonnet 4.6). Long conversations eventually hit it. Customers must:

- Track how many tokens are in the current conversation (model-specific tokenizers, not exposed)
- Decide when to trim and what to keep
- Implement the trim correctly (preserve role alternation, system prompts, etc.)
- Decide whether to summarize (which costs another LLM call) or drop

Most customer code either ignores this until production fails or uses a naive last-N-messages approach that breaks coherence.

### P3. No built-in cost visibility, no per-conversation budget

Bedrock charges per token. Multi-turn amplifies this because the full history is resent each turn. Customers cannot:

- See running costs during a session (token counts are returned but not converted)
- Differentiate regular, cache-read, and cache-write costs in real time
- Set a per-conversation budget (production cost runaways are a top customer concern)
- Estimate the cost impact of a context strategy before adopting it

Customers discover overruns through their bill, not through their application.

### P4. Prompt caching requires careful manual placement

Bedrock's prompt caching can reduce input cost by **up to 90%** and latency by **up to 85%**. Capturing those savings requires:

- Inserting `CachePointBlock` markers at the right positions in the message list
- Knowing the per-model minimum cacheable prefix (1024 tokens for Sonnet, 4096 for Opus)
- Handling the three token buckets in billing (regular input, cache read, cache write)
- Re-placing cache markers correctly after a trim — get it wrong and the cache is invalidated every turn

Most customers either don't know about prompt caching or find it too complex to implement correctly across different models. The savings are left on the table.

### P5. Retry policy is generic; Bedrock has specific failure modes

The SDK's default retry policy treats all errors the same. Bedrock has distinct failure modes that benefit from separate handling:

| Error | Customer benefit from specialized handling |
|-------|--------------------------------------------|
| `ThrottlingException` (429) | Longer backoff, respect token-per-minute limits |
| `ModelNotReadyException` | Short backoff, just a cold start |
| `ModelTimeoutException` | Short backoff, transient |
| `ServiceUnavailableException` | Short backoff |

Without specialization, applications either over-retry on 429 (wasting time and provoking more throttling) or under-retry on transient 5xx (dropping requests).

Customers also benefit from **client-side rate limiting** (token bucket with adaptive backoff) to proactively avoid the server-side limit instead of reacting to it. This is non-trivial to write well and is reimplemented across customer codebases.

### P6. Long conversations accumulate stale context

In customer-support and live-coding scenarios, content from 30 minutes ago is often irrelevant. There is no built-in way to expire old context by time. Customers either pay for the resent stale history or write their own expiry logic.

### P7. The developer experience trails competitive offerings

OpenAI's Assistants API and Anthropic's Messages SDK both provide stateful conversation primitives. Customers explicitly evaluating Bedrock vs alternatives cite the lack of a higher-level Java client as friction:

- "We can build it ourselves but the Anthropic SDK does it out of the box"
- "I have a 200-turn conversation. Why do I have to manage that myself?"

This isn't a Bedrock capability gap — it's a developer experience gap that costs us deals and time-to-first-success.

---

## Solution

A higher-level client — `BedrockEnhancedClient` — that wraps `BedrockRuntimeClient` and packages the right defaults for multi-turn applications. Java first, intended pattern for other SDK languages later.

### Surface area (Java example)

```java
BedrockEnhancedClient client = BedrockEnhancedClient.builder()
    .pricingProvider(PricingProvider.builtIn())
    .contextWindowConfig(c -> c
        .contextStrategy(ContextStrategy.COST_OPTIMIZED_TRIMMING)
        .targetRecentTokens(10_000)         // T = retained recent history after trim
        .maxRecentTokens(20_000))           // M = trigger threshold
    .costBudgetConfig(b -> b
        .budget(0.50)                       // USD per conversation
        .mode(CostBudgetConfig.Mode.ENFORCE))
    .retryConfig(r -> r.throttleBaseDelay(Duration.ofSeconds(2)))
    .throttlingConfig(t -> t.maxRequestsPerSecond(10.0).adaptiveEnabled(true))
    .build();

ChatSession session = client.createSession("anthropic.claude-sonnet-4-5-20250929-v1:0");
ChatResponse resp = session.converse("Tell me about Hogwarts.");
CostEstimate cost = session.costEstimate();          // $0.0042 / saved $0.0381 via caching
BudgetStatus budget = session.budgetStatus();         // $0.0042 / $0.50 (1%)
```

That's the entire surface for the headline use case. All seven problems above are addressed by a single configurable client.

### Solution by problem

| # | Problem | Solution |
|---|---------|----------|
| P1 | Manual state management | `ChatSession` is the single source of truth; `converse(String)` handles append/track/response automatically |
| P2 | Context window overflow | Four strategies: `SLIDING_WINDOW`, `SUMMARIZE`, `COST_OPTIMIZED_TRIMMING`, `COST_OPTIMIZED_SUMMARIZE`. Token-mode (preferred) and turn-mode (fallback). Auto-applied each turn. |
| P3 | No cost visibility | `session.costEstimate()` returns differentiated costs in real time. `CostBudgetConfig` adds a per-conversation USD ceiling with `WARN` and `ENFORCE` modes; ENFORCE attempts a corrective trim before throwing. |
| P4 | Manual cache placement | Cache checkpoints inserted automatically at the end of active messages every turn. Cost-optimized strategies enable caching by default. System-prompt caching is also automatic. |
| P5 | Generic retry policy | Per-failure-mode backoff settings (separate base delays for 429 vs 5xx), full-jitter exponential. Token-bucket rate limiter with adaptive throttling. |
| P6 | Stale context | Optional `maxAge` on `ContextWindowConfig` expires older messages by time. |
| P7 | Developer experience | Three-line setup, model-agnostic API, side-by-side demo, comparable ergonomics to OpenAI Assistants. |

### Cost-optimized strategy in detail

The headline strategy combines context trimming with prompt caching to capture the structural savings the underlying APIs make possible.

**The model:** Customers configure `TARGET (T)` and `MAX (M)` in tokens (or turns). The strategy retains the most recent T tokens, lets the conversation grow up to M, then bulk-trims back to T. Every active message has a cache checkpoint at the end — so the entire active prompt becomes a cached prefix that gets re-read at 10% of the input rate on subsequent turns.

Optional `expectedTotalTurns` enables a cost-justified early trim between T and M when the math says trimming now is cheaper than continuing to grow.

**The math (for transparency to PMT, not customer-facing):**

```
H  = current retained recent-history size (in active unit)
W  = cache write cost ratio (default 2.0 for 1-hour TTL on Sonnet 4.5+)
R  = cache read cost ratio (default 0.10)
E  = remaining turns

If H >= M: trim to T (MAX_REACHED)
Elif T < H < M and E > (W*T) / (R*(H-T)): trim to T (COST_BASED_EARLY_TRIM)
Else: no trim
```

The default values reflect 1-hour TTL pricing as supported on Sonnet 4.5+ and Opus 4.6+. Older models with 5-minute TTL can override.

### Defaults are aggressive on the right things

| Config | Default | Rationale |
|--------|---------|-----------|
| Caching when `COST_OPTIMIZED_*` is selected | Enabled | The whole point of the strategy is caching; off by default would defeat it |
| Cache TTL ratio | 1-hour (W=2.0) | Newer Anthropic models support it and amortize the write cost much faster |
| Retry on `ModelNotReadyException` | Enabled | Cold starts are common; customers should not need to discover this |
| Adaptive throttling | Enabled | Reduces production incidents from token-per-minute spikes |
| Cost budget | Off | Opt-in — customers must set their own ceiling |
| Time-based expiry | Off | Opt-in — semantics are application-specific |

---

## Impact

### Cost

A 20-turn Harry Potter Q&A conversation on Claude Sonnet 4.5, run through the demo:

| Strategy | Total cost | Savings vs Default |
|----------|-----------|--------------------|
| Default (no management) | $0.082 | baseline |
| Sliding Window (C=10) | $0.041 | 50% |
| Cost Optimized (T=10, M=20, caching on) | $0.012 | **85%** |

The cost gap widens with conversation length. At 100 turns the cost-optimized strategy is approximately **90%** cheaper than Default, because cached reads + bounded prefix dominate the math.

These numbers come from the demo app under `bedrock-enhanced-client/bedrock-enhanced-demo`.

### Developer experience

| Metric | Today | With enhanced client |
|--------|-------|----------------------|
| Lines of conversation-management code | 50–100 | 3 |
| Time-to-first-multi-turn-app | hours | minutes |
| Correct cache placement | rare | automatic |
| Per-conversation budget | DIY | one builder method |
| Bedrock-specific retry policy | DIY | one builder method |

### Adoption story

The SDK already has the precedent of higher-level "enhanced" clients (DynamoDB Enhanced, S3 Transfer Manager). A Bedrock Enhanced Client follows the same pattern and naming, lowers the barrier to entry, and gives the SDK team a clean place to ship Bedrock-specific affordances without bloating the low-level `BedrockRuntimeClient`.

---

## Scope

### In scope (prototype today)

- `ChatSession` with managed history, system prompts, inference config
- Four context strategies: `SLIDING_WINDOW`, `SUMMARIZE`, `COST_OPTIMIZED_TRIMMING`, `COST_OPTIMIZED_SUMMARIZE`
- Token mode and turn mode for cost-optimized strategies
- Automatic prompt caching with per-model minimum prefix awareness
- Real-time cost estimation with differentiated rates
- `CostBudgetConfig` (OFF / WARN / ENFORCE) — orthogonal, applies to any strategy
- `TokenBudget` (cumulative token cap)
- Bedrock-specific retry handler + adaptive token-bucket rate limiter
- Time-based message expiry
- Pricing provider abstraction (built-in static table; AWS Pricing API)
- Spring Boot demo app comparing strategies side-by-side with budget bars

### Out of scope (intentionally)

- Tools / function calling orchestration (defer to LangChain4j and similar)
- Agents and multi-step planning
- Streaming response parsing (delegated to underlying `ConverseStream`)
- Persistent session storage (kept in-memory; customer's choice)
- Embeddings and RAG retrieval

### Open design questions for PMT review

1. **Surface placement.** Ship as `software.amazon.awssdk.enhanced.bedrock` mirroring DynamoDB Enhanced, or as a separate `aws-bedrock-runtime-enhanced` artifact?
2. **Cost budget granularity.** Currently client-level. Should it also be settable per-`CreateSessionRequest`?
3. **Strategy enum vs composition.** `ContextStrategy` is currently a closed enum. Should we expose a `ContextManagementStrategy` interface so customers can plug in their own?
4. **Pricing source of truth.** Built-in static table is simpler but goes stale; AWS Pricing API is authoritative but adds a dependency. Today both are supported. Long-term, should the SDK ship pricing in its data files (parallel to endpoints.json)?
5. **Cross-language story.** Java first; what's the right roadmap for Python, JS/TS, .NET? Internal RFC or wait for customer signal?
6. **Tools/agents.** Where does the line between this client and LangChain4j fall? Is the right answer to provide the building blocks here and let LangChain4j compose them?
7. **Bedrock auto-compaction.** Anthropic's auto-compaction (Sonnet 4.5+) overlaps with our `SUMMARIZE` strategy. Should we delegate to it when available and only fall back to client-side summarization for older models?

### Risks

- **Customer confusion if defaults change.** Caching enabled by default for cost-optimized strategies is the right call, but if a customer expects the low-level behavior they could be surprised. Mitigation: explicit naming (`COST_OPTIMIZED_TRIMMING` is unambiguous) and clear docs.
- **Cost of being wrong about pricing.** Built-in static pricing can mis-report cost when models add new tiers. Mitigation: clearly label as estimate, allow Pricing API override, version-pin the static table.
- **Maintenance burden.** Each new model variant means new pricing, new cache minimums, new context window. Mitigation: data-driven configuration, opt-in pricing API.
- **Conflict with existing customer abstractions.** Some customers have already built their own. Mitigation: small surface, opt-in, drop-in replacement; existing code can keep using `BedrockRuntimeClient` directly.

---

## Comparable offerings

| Offering | Stateful chat | Auto cache | Context strategy | Cost budget | Notes |
|----------|---------------|-----------|------------------|-------------|-------|
| OpenAI Assistants API | Yes | Server-side | Server-side | No | Reference for DX |
| Anthropic Messages SDK (Python/TS) | Yes | Manual | DIY | No | Customers cite as easier than Bedrock |
| LangChain4j `ChatMemoryProvider` | Yes | DIY | Pluggable | No | Generic, model-agnostic; doesn't know about Bedrock caching |
| AWS DynamoDB Enhanced Client | n/a | n/a | n/a | n/a | Architectural precedent for AWS-side higher-level client |
| **Bedrock Enhanced Client (this proposal)** | Yes | Auto | 4 built-in | Yes | Opinionated about Bedrock-specific cost optimizations |

---

## Asks of PMT

1. **Validate the customer pain.** Does this match what you hear from Bedrock customers, especially Java enterprise accounts?
2. **Validate the surface area.** Are the seven problems and seven solutions the right set, or is something missing/extraneous?
3. **Productization signal.** Is this worth investing in as an officially supported AWS abstraction, vs leaving it as an experimental project with community uptake?
4. **Cross-team alignment.** Who in the Bedrock org should review (product, applied science for the cost model, eng for the cross-SDK story)?
5. **Naming and positioning.** Is "Bedrock Enhanced Client" the right name, or should it be something else (Bedrock Conversation Client, Bedrock Chat Client, etc.)?
6. **Cross-language commitment.** If we productize for Java, should we commit to mirror in Python and JS/TS within N quarters, or proceed Java-first and treat that as the validation?

---

## Appendix A — Repository

- Code: https://github.com/goabv/bedrock-enhanced-client
- Demo: `bedrock-enhanced-demo/` — Spring Boot app, side-by-side comparison
- Slides: `bedrock-enhanced-demo/src/main/resources/static/slides.html`

## Appendix B — Glossary

- **T (TARGET)**: Tokens (or turns) retained after a trim
- **M (MAX)**: Tokens (or turns) at which a trim is triggered
- **W (cache write cost ratio)**: Cost of writing one token to the cache, relative to a regular input token
- **R (cache read cost ratio)**: Cost of reading one token from the cache, relative to a regular input token
- **E (expectedTotalTurns)**: Optional customer hint for total conversation length, enables cost-justified early trim
- **TTL**: Cache time-to-live (5 minutes for older Anthropic models, 1 hour for Sonnet 4.5+ and Opus 4.6+)

## Appendix C — Math walkthrough for the cost-optimized strategy

For a conversation of length E remaining turns, with retained recent history H (tokens) and TARGET T:

- **Continue without trim**: each turn re-sends H tokens at the cache-read rate, plus an average new turn at the input rate. Forecast cost ≈ `R × (E×H + A×E×(E-1)/2) + write/output costs`.
- **Trim now to T**: pay one cache-write cost for the new T-token prefix, then each subsequent turn re-sends T tokens at the cache-read rate. Forecast cost ≈ `W × T + R × (E×T + A×E×(E-1)/2) + write/output costs`.

The break-even point is `E > (W × T) / (R × (H - T))`, which is the early-trim trigger when `expectedTotalTurns` is configured. With default `R=0.10` and `W=2.0` (1-hour TTL), the threshold is gentle — most long conversations qualify.

Without `expectedTotalTurns`, the strategy is conservative and only trims at H ≥ M.
