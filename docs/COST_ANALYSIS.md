# Cost Analysis: Caching + Trimming vs Caching Only

## Assumptions

- **Model:** Claude Sonnet 4 (5-min TTL)
- **Pricing per 1K tokens:**
  - Input: $0.003
  - Cache Read: $0.0003 (10% of input)
  - Cache Write: $0.00375 (125% of input)
  - Output: $0.015
- **Average turn size:** 500 tokens (200 user + 300 assistant)
- **System prompt:** 50 tokens

---

## Per-Turn Cost Formulas

**Strategy A — Cache Everything, Never Trim:**

Every turn, the full prefix is cached. New content gets cache-written.

```
Per-turn cost at turn N:
  = (prefix_tokens × cache_read_rate) + (new_tokens × cache_write_rate)
  = (N × 500 × $0.0003/1K) + (500 × $0.00375/1K)
  = $0.00015N + $0.001875
```

**Strategy B — Trim at C, then cache frozen prefix:**

After trim, frozen prefix (C × 500 tokens) is cached. New messages pay full input rate.

```
Per-turn cost at turn N (where N > C, after trim):
  = (frozen × cache_read_rate) + (uncached × input_rate) + (new × cache_write_rate)
  = (C×500 × $0.0003/1K) + ((N-C)×500 × $0.003/1K) + (500 × $0.00375/1K)
  = $0.00015C + $0.0015(N-C) + $0.001875
```

---

## Break-Even: Per-Turn Comparison

When is Strategy B cheaper than Strategy A at turn N?

```
Strategy A: $0.00015N + $0.001875
Strategy B: $0.00015C + $0.0015(N-C) + $0.001875

B < A when:
0.00015C + 0.0015(N-C) < 0.00015N
0.00015C + 0.0015N - 0.0015C < 0.00015N
0.0015N - 0.00135C < 0.00015N
0.00135N < 0.00135C
N < C
```

**Result: Strategy B is NEVER cheaper per-turn than Strategy A (for N > C).**

This makes sense: 10% cache-read rate on a large prefix is always cheaper than 100% input rate on uncached messages.

---

## So When Does Trimming Help?

The per-turn analysis above assumes the cache never expires. In reality:

### Factor 1: Cache TTL Expiry

With 5-min TTL, if turns take > 5 minutes, the cache expires. On the next turn:
- **No trim:** Must re-cache the ENTIRE prefix. Cost = full_prefix × cache_write_rate
- **Trimmed:** Only re-cache the smaller frozen prefix. Cost = C×500 × cache_write_rate

**Re-cache cost comparison at turn N:**
```
No trim re-cache:  N × 500 × $0.00375/1K = $0.001875N
Trimmed re-cache:  C × 500 × $0.00375/1K = $0.001875C
Savings per expiry: $0.001875 × (N - C)
```

With C=10 at turn 30: savings = $0.001875 × 20 = **$0.0375 per cache expiry event**.

### Factor 2: Context Window Limit

Models have a max context (200K tokens for Sonnet). At 500 tokens/turn:
- 200K / 500 = 400 turns max without trimming
- With trimming at C=10: unlimited turns (always stays at ~5K tokens)

### Factor 3: Cumulative Cost Over Many Turns

**30-turn conversation, cache never expires (fast-paced):**

| Strategy | Total Cost (sum of all turns) |
|----------|------------------------------|
| Cache everything | Σ($0.00015n + $0.001875) for n=1..30 = $0.126 |
| Trim at C=5 | Turns 1-5: ~$0.012 + Turns 6-30: Σ($0.00015×5 + $0.0015×(n-5) + $0.001875) = $0.012 + $0.59 = **$0.60** |
| Trim at C=10 | Turns 1-10: ~$0.027 + Turns 11-30: Σ($0.0015 + $0.0015×(n-10) + $0.001875) = $0.027 + $0.38 = **$0.41** |
| Trim at C=20 | Turns 1-20: ~$0.069 + Turns 21-30: Σ($0.003 + $0.0015×(n-20) + $0.001875) = $0.069 + $0.057 = **$0.126** |

**Cache everything wins when cache doesn't expire!**

---

**30-turn conversation, cache expires every 3 turns (slow-paced, 5-min TTL):**

Each expiry forces a full re-cache of the prefix.

| Strategy | Re-cache events | Re-cache cost | Regular turn cost | Total |
|----------|----------------|---------------|-------------------|-------|
| Cache everything | 10 events | Σ(n×500×$0.00375/1K) ≈ $0.28 | $0.126 | **$0.41** |
| Trim at C=10 | 7 events (after trim) | 7 × 5000×$0.00375/1K = $0.13 | $0.41 | **$0.54** |
| Trim at C=5 | 8 events | 8 × 2500×$0.00375/1K = $0.075 | $0.60 | **$0.67** |

Still worse with trimming! The uncached portion paying full rate dominates.

---

## The Real Answer

**For pure cost optimization with Anthropic's current pricing (cache read = 10% of input):**

> **Caching everything without trimming is almost always the cheapest option**, as long as you stay within the model's context window.

**The formula and trimming become relevant when:**

1. **You hit the context window limit** — Must trim. The formula optimizes WHEN to trim to minimize the cost spike.

2. **You want to control context window growth** — Even if not at the limit, you may want bounded memory usage. The formula finds the economically optimal trim point.

3. **Cache write rate changes** — If cache writes become more expensive (higher α), trimming a smaller prefix becomes more attractive.

4. **Different pricing models** — If cache-read rate were higher (say 30% instead of 10%), trimming would break even sooner.

---

## Summary Table

| C value | Best for | Trade-off |
|---------|----------|-----------|
| No trim (∞) | Short-medium conversations, fast pace | Cheapest but unbounded growth |
| C = 20 | Long conversations near context limit | Minimal cost overhead, good context |
| C = 10 | Very long conversations, need bounded memory | Moderate cost, loses older context |
| C = 5 | Extreme length, minimal memory | Higher per-turn cost, very limited context |

---

## Recommendation for the Demo

For a 20-turn demo with fast-paced turns (cache never expires):
- **Cache everything** will show the lowest cost
- **Sliding window (no cache)** will show the highest cost
- **Cost-optimized trim** will be between the two — it trims unnecessarily for short conversations

The cost-optimized strategy shines in **production workloads** with:
- Conversations that run for 100+ turns
- Turns that may be minutes apart (cache expiry risk)
- Need to stay within context window bounds
