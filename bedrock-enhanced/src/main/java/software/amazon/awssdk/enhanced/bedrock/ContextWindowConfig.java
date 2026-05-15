/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.enhanced.bedrock;

import java.time.Duration;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;

/**
 * Configuration for conversation context window management.
 *
 * <p>Two overflow policies control what happens when the context window cap is reached:
 * <ul>
 *     <li>{@link OverflowPolicy#TRIM} (default) — automatically reduces the context
 *         using the configured {@link ContextStrategy}.</li>
 *     <li>{@link OverflowPolicy#REJECT} — throws {@link ContextWindowExceededException},
 *         giving the caller full control.</li>
 * </ul>
 *
 * <p>The sliding window strategy supports Cmin/Cmax semantics:
 * <ul>
 *     <li>{@code maxMessages} (Cmax) — the trigger point. When message count exceeds this, trimming fires.</li>
 *     <li>{@code minMessages} (Cmin) — the trim target. After trimming, this many messages are retained.</li>
 * </ul>
 * If only {@code maxMessages} is set, {@code minMessages} defaults to the same value (trim one pair at a time).
 *
 * <p>A time-based {@code maxAge} can be set to expire messages older than a duration,
 * independent of token/message limits. Expired messages are removed before each turn.
 */
@SdkPublicApi
public final class ContextWindowConfig {

    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final int DEFAULT_MAX_MESSAGES = 100;
    private static final ContextStrategy DEFAULT_CONTEXT_STRATEGY = ContextStrategy.SLIDING_WINDOW;
    private static final OverflowPolicy DEFAULT_OVERFLOW_POLICY = OverflowPolicy.TRIM;

    // Defaults for cost-optimized strategies (1-hour TTL — used by Sonnet 4.5+, Opus 4.6+)
    private static final double DEFAULT_CACHE_READ_RATIO = 0.10;
    private static final double DEFAULT_CACHE_WRITE_RATIO = 2.0;

    private final int maxTokens;
    private final int maxMessages;
    private final int minMessages;
    private final ContextStrategy contextStrategy;
    private final OverflowPolicy overflowPolicy;
    private final Duration maxAge;
    private final double cacheReadCostRatio;
    private final double cacheWriteCostRatio;
    private final Integer expectedTotalTurns;
    private final Integer targetRecentTokens;
    private final Integer maxRecentTokens;

    private ContextWindowConfig(Builder builder) {
        this.maxTokens = Validate.isPositive(builder.maxTokens, "maxTokens");
        this.maxMessages = Validate.isPositive(builder.maxMessages, "maxMessages");
        this.minMessages = builder.minMessages > 0 ? builder.minMessages : builder.maxMessages;
        this.contextStrategy = Validate.paramNotNull(builder.contextStrategy, "contextStrategy");
        this.overflowPolicy = Validate.paramNotNull(builder.overflowPolicy, "overflowPolicy");
        this.maxAge = builder.maxAge;
        this.cacheReadCostRatio = builder.cacheReadCostRatio;
        this.cacheWriteCostRatio = builder.cacheWriteCostRatio;
        this.expectedTotalTurns = builder.expectedTotalTurns;
        this.targetRecentTokens = builder.targetRecentTokens;
        this.maxRecentTokens = builder.maxRecentTokens;

        if (this.minMessages > this.maxMessages) {
            throw new IllegalArgumentException("minMessages (" + this.minMessages
                + ") must not exceed maxMessages (" + this.maxMessages + ")");
        }
        if (this.cacheReadCostRatio <= 0) {
            throw new IllegalArgumentException("cacheReadCostRatio must be > 0");
        }
        if (this.cacheWriteCostRatio < 0) {
            throw new IllegalArgumentException("cacheWriteCostRatio must be >= 0");
        }
        // Validate token-mode thresholds if provided
        if (this.targetRecentTokens != null && this.targetRecentTokens <= 0) {
            throw new IllegalArgumentException("targetRecentTokens must be > 0");
        }
        if (this.maxRecentTokens != null && this.targetRecentTokens != null
            && this.maxRecentTokens <= this.targetRecentTokens) {
            throw new IllegalArgumentException("maxRecentTokens must be > targetRecentTokens");
        }
        if ((this.targetRecentTokens == null) != (this.maxRecentTokens == null)) {
            throw new IllegalArgumentException(
                "targetRecentTokens and maxRecentTokens must both be set or both be null");
        }
    }

    /**
     * Maximum estimated tokens allowed in the context window before trimming or rejection.
     */
    public int maxTokens() {
        return maxTokens;
    }

    /**
     * Maximum number of messages (Cmax). When exceeded, trimming fires.
     */
    public int maxMessages() {
        return maxMessages;
    }

    /**
     * Minimum number of messages to retain after trimming (Cmin). Defaults to
     * {@code maxMessages} if not explicitly set, which gives the classic one-pair-at-a-time
     * sliding window behavior.
     */
    public int minMessages() {
        return minMessages;
    }

    /**
     * The strategy used to manage context when the cap is reached (only applies
     * when overflow policy is {@link OverflowPolicy#TRIM}).
     */
    public ContextStrategy contextStrategy() {
        return contextStrategy;
    }

    /**
     * The policy applied when the context window cap is reached.
     */
    public OverflowPolicy overflowPolicy() {
        return overflowPolicy;
    }

    /**
     * Optional maximum age for messages. Messages older than this duration are
     * removed before each conversation turn, regardless of token or message limits.
     * Returns {@code null} if no time-based expiry is configured.
     */
    public Duration maxAge() {
        return maxAge;
    }

    /**
     * Cache read cost ratio (R) — cost of cached input tokens divided by normal input cost.
     * Used by cost-optimized strategies to compute the break-even point.
     * Defaults to 0.10 (10% of input cost) if not set.
     */
    public double cacheReadCostRatio() {
        return cacheReadCostRatio;
    }

    /**
     * Cache write cost ratio (W) — cost of writing tokens to cache divided by normal input cost.
     * Used by cost-optimized strategies to compute the break-even point.
     * Defaults to 2.0 (1-hour TTL — Sonnet 4.5+, Opus 4.6+). Use 1.25 for 5-minute TTL.
     */
    public double cacheWriteCostRatio() {
        return cacheWriteCostRatio;
    }

    /**
     * Optional expected total turns for the conversation. When set, cost-optimized
     * strategies use this to decide whether to trim early between TARGET (T=minMessages/2)
     * and MAX (M=maxMessages/2). Returns {@code null} if not configured.
     */
    public Integer expectedTotalTurns() {
        return expectedTotalTurns;
    }

    /**
     * Optional token-mode TARGET threshold. When set together with {@link #maxRecentTokens()},
     * cost-optimized strategies use token-based TARGET/MAX (preferred over turn-based).
     * Returns {@code null} if turn mode should be used.
     */
    public Integer targetRecentTokens() {
        return targetRecentTokens;
    }

    /**
     * Optional token-mode MAX threshold. Must be > {@link #targetRecentTokens()}.
     * Returns {@code null} if turn mode should be used.
     */
    public Integer maxRecentTokens() {
        return maxRecentTokens;
    }

    /**
     * Strategy-level break-even threshold for planning purposes (NOT used at runtime).
     *
     * <p>Returns the {@code expectedTotalTurns} value above which Strategy C
     * (TARGET/MAX trimming + tail caching) is cheaper than never trimming.
     *
     * <p>Formula: {@code T + M + (2*W*T) / (R*(M-T))}
     *
     * <p>Where T = {@code minMessages/2}, M = {@code maxMessages/2}.
     */
    public double costOptimizedBreakEvenThreshold() {
        int t = minMessages / 2;
        int m = maxMessages / 2;
        if (t < 1) t = 1;
        if (m <= t) m = t + 1;
        return t + m + (2.0 * cacheWriteCostRatio * t) / (cacheReadCostRatio * (m - t));
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return ToString.builder("ContextWindowConfig")
                       .add("maxTokens", maxTokens)
                       .add("maxMessages", maxMessages)
                       .add("minMessages", minMessages)
                       .add("contextStrategy", contextStrategy)
                       .add("overflowPolicy", overflowPolicy)
                       .add("maxAge", maxAge)
                       .build();
    }

    /**
     * Strategy for managing conversation context window when limits are reached.
     */
    public enum ContextStrategy {
        /**
         * When Cmax is exceeded, removes oldest user/assistant message pairs until
         * message count is at or below Cmin. If Cmin == Cmax (the default), this
         * removes one pair at a time. If Cmin &lt; Cmax, trims in bulk down to Cmin.
         */
        SLIDING_WINDOW,

        /**
         * When the context window cap is reached, summarizes all older messages into
         * a single assistant message using the model. The summary replaces the older
         * messages, preserving semantic context while freeing token space.
         */
        SUMMARIZE,

        /**
         * Cost-optimized strategy that coordinates trimming with prompt caching to
         * minimize average input token cost per turn. Uses a threshold derived from
         * cache pricing: trims in bulk (down to {@code minMessages}) when
         * accumulated uncached tokens exceed the threshold, then freezes the remaining
         * turns as a cacheable prefix.
         *
         * <p>This strategy automatically adapts to conversation length and turn size.
         * It requires exact token counts from Bedrock responses (not heuristics).
         *
         * <p>Works best with prompt caching enabled. When caching is disabled, behaves
         * similarly to sliding window but with bulk trimming.
         */
        COST_OPTIMIZED_TRIMMING,

        /**
         * Cost-optimized strategy that coordinates summarization with prompt caching.
         * When the coherence floor is breached, older messages are summarized into a
         * single condensed message (via the model), then the summary is frozen as a
         * cacheable prefix. Subsequent turns pay cache-read rates on the stable summary.
         *
         * <p>Combines the semantic preservation of {@link #SUMMARIZE} with the cost
         * benefits of prompt caching. The summary is compact and stable, making it an
         * ideal cache candidate.
         */
        COST_OPTIMIZED_SUMMARIZE
    }

    /**
     * Policy for handling context window overflow.
     */
    public enum OverflowPolicy {
        /**
         * Automatically reduce context using the configured {@link ContextStrategy}.
         * This is the default.
         */
        TRIM,

        /**
         * Reject the conversation turn by throwing {@link ContextWindowExceededException}.
         * No messages are removed. The caller decides how to handle the overflow.
         */
        REJECT
    }

    public static final class Builder {
        private int maxTokens = DEFAULT_MAX_TOKENS;
        private int maxMessages = DEFAULT_MAX_MESSAGES;
        private int minMessages = 0; // 0 means "default to maxMessages"
        private ContextStrategy contextStrategy = DEFAULT_CONTEXT_STRATEGY;
        private OverflowPolicy overflowPolicy = DEFAULT_OVERFLOW_POLICY;
        private Duration maxAge;
        private double cacheReadCostRatio = DEFAULT_CACHE_READ_RATIO;
        private double cacheWriteCostRatio = DEFAULT_CACHE_WRITE_RATIO;
        private Integer expectedTotalTurns;
        private Integer targetRecentTokens;
        private Integer maxRecentTokens;

        private Builder() {
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        /**
         * Sets the maximum number of messages (Cmax). When the message count exceeds
         * this value, trimming is triggered.
         */
        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        /**
         * Sets the minimum number of messages to retain after trimming (Cmin).
         * When trimming fires, messages are removed until the count is at or below
         * this value. Defaults to {@code maxMessages} if not set, giving classic
         * one-pair-at-a-time behavior.
         *
         * <p>Setting Cmin &lt; Cmax enables bulk trimming: the window grows from
         * Cmin to Cmax, then trims back to Cmin in one operation.
         */
        public Builder minMessages(int minMessages) {
            this.minMessages = minMessages;
            return this;
        }

        public Builder contextStrategy(ContextStrategy contextStrategy) {
            this.contextStrategy = contextStrategy;
            return this;
        }

        public Builder overflowPolicy(OverflowPolicy overflowPolicy) {
            this.overflowPolicy = overflowPolicy;
            return this;
        }

        /**
         * Sets the maximum age for messages. Messages older than this duration are
         * removed before each conversation turn. Set to {@code null} to disable
         * time-based expiry (the default).
         */
        public Builder maxAge(Duration maxAge) {
            this.maxAge = maxAge;
            return this;
        }

        /**
         * Sets the cache read cost ratio (R) — cost of cached input tokens divided by
         * normal input cost. Used by cost-optimized strategies. Defaults to 0.10.
         */
        public Builder cacheReadCostRatio(double cacheReadCostRatio) {
            this.cacheReadCostRatio = cacheReadCostRatio;
            return this;
        }

        /**
         * Sets the cache write cost ratio (W) — cost of writing tokens to cache divided
         * by normal input cost. Used by cost-optimized strategies.
         * Defaults to 2.0 (1-hour TTL — Sonnet 4.5+, Opus 4.6+). Use 1.25 for 5-minute TTL.
         */
        public Builder cacheWriteCostRatio(double cacheWriteCostRatio) {
            this.cacheWriteCostRatio = cacheWriteCostRatio;
            return this;
        }

        /**
         * Optional: sets the expected total turns for the conversation. Cost-optimized
         * strategies use this to decide whether to trim early between TARGET and MAX.
         * If absent, only MAX-trimming is performed.
         */
        public Builder expectedTotalTurns(Integer expectedTotalTurns) {
            this.expectedTotalTurns = expectedTotalTurns;
            return this;
        }

        /**
         * Optional: sets the token-mode TARGET threshold. When set together with
         * {@link #maxRecentTokens(Integer)}, cost-optimized strategies use token-based
         * TARGET/MAX (preferred). If only turn thresholds are set, turn mode is used.
         */
        public Builder targetRecentTokens(Integer targetRecentTokens) {
            this.targetRecentTokens = targetRecentTokens;
            return this;
        }

        /**
         * Optional: sets the token-mode MAX threshold. Must be greater than
         * {@code targetRecentTokens}.
         */
        public Builder maxRecentTokens(Integer maxRecentTokens) {
            this.maxRecentTokens = maxRecentTokens;
            return this;
        }

        public ContextWindowConfig build() {
            return new ContextWindowConfig(this);
        }
    }
}
