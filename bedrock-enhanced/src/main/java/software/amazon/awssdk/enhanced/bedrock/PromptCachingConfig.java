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

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.ToString;

/**
 * Configuration for Bedrock prompt caching. When enabled, cache checkpoints are
 * automatically inserted into the conversation to allow Bedrock to reuse cached
 * prompt prefixes, reducing latency and input token costs.
 *
 * <p>By default, a cache checkpoint is placed after the last assistant message
 * in each turn ({@link CachingStrategy#CHECKPOINT_EVERY_TURN}). This ensures
 * the entire conversation prefix up to the previous turn is cacheable.
 *
 * <h2>Usage</h2>
 * {@snippet :
 *     BedrockEnhancedClient client = BedrockEnhancedClient.builder()
 *         .bedrockRuntimeClient(runtimeClient)
 *         .promptCachingConfig(PromptCachingConfig.builder()
 *             .enabled(true)
 *             .build())
 *         .build();
 * }
 */
@SdkPublicApi
public final class PromptCachingConfig {

    private final boolean enabled;
    private final CachingStrategy cachingStrategy;
    private final boolean cacheSystemPrompt;

    private PromptCachingConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.cachingStrategy = builder.cachingStrategy;
        this.cacheSystemPrompt = builder.cacheSystemPrompt;
    }

    /**
     * Whether prompt caching is enabled. Defaults to {@code true}.
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * The strategy for placing cache checkpoints. Defaults to
     * {@link CachingStrategy#CHECKPOINT_EVERY_TURN}.
     */
    public CachingStrategy cachingStrategy() {
        return cachingStrategy;
    }

    /**
     * Whether to place a cache checkpoint on the system prompt. Defaults to {@code true}.
     * This caches the system prompt prefix so it doesn't need to be reprocessed each turn.
     */
    public boolean cacheSystemPrompt() {
        return cacheSystemPrompt;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return ToString.builder("PromptCachingConfig")
                       .add("enabled", enabled)
                       .add("cachingStrategy", cachingStrategy)
                       .add("cacheSystemPrompt", cacheSystemPrompt)
                       .build();
    }

    /**
     * Strategy for placing cache checkpoints in the conversation.
     */
    public enum CachingStrategy {
        /**
         * Places a cache checkpoint after the last assistant message before each
         * new user turn. This is the default and ensures the full conversation
         * prefix is cached between turns.
         */
        CHECKPOINT_EVERY_TURN,

        /**
         * Places a cache checkpoint only on the system prompt (if present).
         * Useful when the system prompt is large and the conversation is short.
         */
        SYSTEM_PROMPT_ONLY
    }

    public static final class Builder {
        private boolean enabled = true;
        private CachingStrategy cachingStrategy = CachingStrategy.CHECKPOINT_EVERY_TURN;
        private boolean cacheSystemPrompt = true;

        private Builder() {
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder cachingStrategy(CachingStrategy cachingStrategy) {
            this.cachingStrategy = cachingStrategy;
            return this;
        }

        public Builder cacheSystemPrompt(boolean cacheSystemPrompt) {
            this.cacheSystemPrompt = cacheSystemPrompt;
            return this;
        }

        public PromptCachingConfig build() {
            return new PromptCachingConfig(this);
        }
    }
}
