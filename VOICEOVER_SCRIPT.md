# Voiceover Script — Bedrock Enhanced Client Presentation

Total target: ~3 minutes (excluding demo recording)

---

## Slide 1: Title (8 seconds)

"Let me walk you through the Bedrock Enhanced Client — a high-level client for stateful conversation management in the AWS SDK for Java v2."

---

## Slide 2: Problem Statement (35 seconds)

"The Bedrock Converse API is stateless — you resend the full conversation history every turn, so costs grow linearly. Models have finite context windows, and without management, long conversations just fail, degrade in context or become very expensive.

Prompt caching exists but isn't on by default, and each model family handles it differently. On top of that, standard retry strategies aren't enough — Bedrock needs token-per-minute aware throttling with adaptive backoff. Developers end up solving all of this themselves, differently, every time."

---

## Slide 3: The Solution (20 seconds)

"So we built the Bedrock Enhanced Client. It wraps the low-level client and manages conversation state, context trimming, caching, and retries — all behind a model-agnostic interface. Three lines of code to set up, and the client handles the rest."

---

## Slide 4: Features Overview (30 seconds)

"Quick feature overview. Context window management with five trim strategies and actual token counts from Bedrock — no heuristics. Automatic prompt caching with real-time cost tracking and token budget enforcement. Model-aware throttling that goes beyond standard retries. And a familiar developer experience — same builder pattern as other SDK enhanced clients and high-level libraries, but model-agnostic so you don't worry about per-model differences."

---

## Slide 5: Architecture (20 seconds)

"The architecture is straightforward. The client is thread-safe and creates sessions. Each session owns a context window manager, token tracker, retry handler, and rate limiter. When you call converse, it handles cache injection, retries, token tracking, and trimming — all in one call."

---

## Slide 6: Context Window Strategies (25 seconds)

"Five strategies for when context hits the cap. Sliding window drops oldest messages. Summarize condenses older messages via the model. Chunked keeps recent fixed-size chunks. Cost-optimized coordinates trimming with prompt caching for lowest cost. And drop-oldest for stateless use cases."

---

## Slide 7: Cost Optimized Deep Dive (25 seconds)

"The cost-optimized strategy is the highlight. It automatically inserts cache checkpoints each turn, so the conversation prefix gets cached. Subsequent turns pay about ten percent of normal input rates. When uncached tokens build up, it trims in bulk and freezes the rest as a cacheable prefix. Zero code changes — just set the strategy and enable caching."

---

## Slide 8: Demo Transition (7 seconds)

"Let me show you this in action — a twenty-turn conversation comparing Default, Sliding Window, and Cost Optimized side by side"

---

*[Demo recording here]*

---

## After Demo: Closing (15 seconds)

"As you saw, the default cost keeps growing, sliding window caps it but loses context, and cost-optimized gives you bounded context with caching that drives down per-turn cost. Switching strategies is one line of config. That's the Bedrock Enhanced Client — thanks for watching."
