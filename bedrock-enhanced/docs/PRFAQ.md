# Bedrock Enhanced Client for AWS SDK for Java v2 — PR/FAQ

## Press Release

### Amazon Bedrock Enhanced Client Simplifies Multi-Turn AI Conversations for Java Developers

**Seattle, WA** — Today, the AWS SDK for Java team announces the Bedrock Enhanced Client, a high-level client library that eliminates the undifferentiated heavy lifting of building conversational AI applications on Amazon Bedrock. Developers using the Bedrock Converse API today must manually manage conversation history, handle context window limits, implement retry logic for throttling, track token usage for cost control, and configure prompt caching — all of which require significant boilerplate code that is error-prone and difficult to get right.

The Bedrock Enhanced Client wraps the low-level `BedrockRuntimeClient` and provides a stateful `ChatSession` abstraction that handles all of this automatically. Developers create a session, call `session.converse("Hello")`, and the client manages the rest — maintaining conversation history, trimming or summarizing when context limits are reached, caching prompt prefixes for cost savings, tracking token usage, and estimating running costs.

"Every team building multi-turn chat on Bedrock ends up writing the same conversation management code," said the AWS SDK team. "The Enhanced Client packages these patterns into a tested, configurable library so developers can focus on their application logic instead of infrastructure plumbing."

The client is available as the `bedrock-enhanced` module in the AWS SDK for Java v2, following the same patterns as `dynamodb-enhanced` and `s3-transfer-manager`.

## Key Features

**Conversation Context Window Management** — Four configurable strategies for handling conversations that exceed token or message limits:
- Sliding Window: gradually drops oldest message pairs
- Summarize: uses the model itself to compress older messages into a summary
- Chunked: retains the most recent N chunks of messages
- Drop Oldest Keep Last: aggressive reset keeping only the latest message

Developers can also choose between TRIM (automatic reduction) and REJECT (throw exception) overflow policies, and set time-based message expiry for fast-paced conversations.

**Automatic Prompt Caching** — Cache checkpoints are automatically inserted into conversation requests, enabling Bedrock's prompt caching to reduce latency by up to 85% and input token costs by up to 90% for supported models. Enabled by default with configurable strategies.

**Token Budget Enforcement** — Set a maximum token budget per session. The client tracks cumulative usage and throws `TokenBudgetExceededException` before the budget is exceeded, preventing runaway costs.

**Running Cost Estimation** — Real-time cost tracking with support for differentiated pricing across regular input tokens, cache-read tokens, cache-write tokens, and output tokens. Two pricing providers: live AWS Pricing API or a built-in static table.

**Bedrock-Specific Retry Policies** — Separate backoff strategies for throttling vs transient errors, with full-jitter exponential backoff.

**Client-Side Rate Limiting** — Token-bucket rate limiter with adaptive throttling to proactively avoid server-side throttling.

---

## Frequently Asked Questions

### Customer FAQ

**Q: What problem does this solve?**
A: Building multi-turn conversational applications on Bedrock requires managing conversation history, handling context window limits, implementing retries, tracking costs, and configuring caching. Each of these is 50-200 lines of code that every team writes from scratch. The Enhanced Client packages all of this into a single, tested library.

**Q: How does context window management work?**
A: The Bedrock Converse API is stateless — you resend the entire conversation history with every request. As conversations grow, this history can exceed the model's context limit. The Enhanced Client automatically tracks the actual token count (using Bedrock's response metadata, not heuristics) and applies your chosen trim strategy when the configured cap is reached.

**Q: What happens when summarization triggers?**
A: When the context window cap is hit and the SUMMARIZE strategy is configured, the client makes an additional Bedrock call to summarize all older messages into a single concise message. This summary replaces the older messages in the history, preserving semantic context while freeing token space. The conversation then continues with the summary as context.

**Q: How does prompt caching save money?**
A: In multi-turn conversations, you resend the entire history each turn. Without caching, Bedrock reprocesses all those tokens every time. With caching, Bedrock stores the prompt prefix and reuses it on subsequent turns, charging cache-read tokens at ~10% of the regular input rate. The Enhanced Client automatically inserts cache checkpoints at the right positions.

**Q: Can I control costs?**
A: Yes, three ways: (1) Token budget per session — hard cap that stops the conversation. (2) Context window limits — controls how much history is sent per turn. (3) Cost estimation — `session.costEstimate()` gives you real-time running costs so you can make decisions in your application logic.

**Q: Does this work with all Bedrock models?**
A: It works with any model that supports the Converse API. Prompt caching requires a supported model (Claude 3+, etc.) and has minimum token thresholds. Cost estimation requires either the AWS Pricing API or a model listed in the built-in pricing table.

**Q: Is it thread-safe?**
A: The `BedrockEnhancedClient` is thread-safe and can be shared. Individual `ChatSession` instances are not thread-safe — use one per thread or synchronize externally.

**Q: How do I migrate from the low-level client?**
A: Replace your manual conversation history management with `ChatSession`. Instead of building `ConverseRequest` with a messages list, call `session.converse("message")`. The session handles history, system prompts, inference config, and caching automatically. You can still customize individual requests via the `converse(Consumer<ConverseRequest.Builder>)` overload.

### Internal FAQ

**Q: Why build this as part of the SDK instead of a separate library?**
A: Following the precedent of `dynamodb-enhanced` and `s3-transfer-manager`, high-level clients that wrap low-level SDK clients belong in the SDK. This ensures consistent versioning, build tooling, testing standards, and discoverability.

**Q: What's the testing strategy?**
A: Unit tests with Mockito for all session logic (9 tests covering single/multi-turn, token tracking, budget enforcement, reset, content blocks, request overrides). Integration tests that make real Bedrock API calls to verify end-to-end behavior. A Spring Boot demo app for interactive testing of all features.

**Q: What are the dependencies?**
A: `bedrockruntime` (required), `pricing` (optional, for API-based cost estimation), `sdk-core`, `annotations`, `utils`, `regions`, `auth`. No external dependencies beyond the SDK.
