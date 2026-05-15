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

package software.amazon.awssdk.enhanced.bedrock.internal;

import java.util.Collections;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.BedrockEnhancedClient;
import software.amazon.awssdk.enhanced.bedrock.BedrockRetryConfig;
import software.amazon.awssdk.enhanced.bedrock.ChatSession;
import software.amazon.awssdk.enhanced.bedrock.ContextWindowConfig;
import software.amazon.awssdk.enhanced.bedrock.CreateSessionRequest;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;
import software.amazon.awssdk.enhanced.bedrock.PromptCachingConfig;
import software.amazon.awssdk.enhanced.bedrock.ThrottlingConfig;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.utils.Logger;
import software.amazon.awssdk.utils.Validate;

/**
 * Default implementation of {@link BedrockEnhancedClient}.
 */
@SdkInternalApi
public final class DefaultBedrockEnhancedClient implements BedrockEnhancedClient {

    private static final Logger log = Logger.loggerFor(DefaultBedrockEnhancedClient.class);

    private final BedrockRuntimeClient bedrockRuntimeClient;
    private final boolean isDefaultClient;
    private final ContextWindowConfig contextWindowConfig;
    private final ThrottlingConfig throttlingConfig;
    private final BedrockRetryConfig retryConfig;
    private final PromptCachingConfig promptCachingConfig;
    private final PricingProvider pricingProvider;
    private final TokenBucketRateLimiter rateLimiter;

    private DefaultBedrockEnhancedClient(DefaultBuilder builder) {
        if (builder.bedrockRuntimeClient != null) {
            this.bedrockRuntimeClient = builder.bedrockRuntimeClient;
            this.isDefaultClient = false;
        } else {
            this.bedrockRuntimeClient = BedrockRuntimeClient.create();
            this.isDefaultClient = true;
        }

        this.contextWindowConfig = builder.contextWindowConfig != null
                                   ? builder.contextWindowConfig
                                   : ContextWindowConfig.builder().build();

        this.retryConfig = builder.retryConfig != null
                           ? builder.retryConfig
                           : BedrockRetryConfig.builder().build();

        // For cost-optimized strategies, default caching to enabled if user didn't configure it
        if (builder.promptCachingConfig != null) {
            this.promptCachingConfig = builder.promptCachingConfig;
        } else if (this.contextWindowConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_TRIMMING
                   || this.contextWindowConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_SUMMARIZE) {
            this.promptCachingConfig = PromptCachingConfig.builder()
                .enabled(true)
                .cacheSystemPrompt(true)
                .build();
        } else {
            this.promptCachingConfig = PromptCachingConfig.builder().build();
        }

        this.pricingProvider = builder.pricingProvider;

        this.throttlingConfig = builder.throttlingConfig;

        if (throttlingConfig != null) {
            this.rateLimiter = new TokenBucketRateLimiter(
                throttlingConfig.maxRequestsPerSecond(),
                throttlingConfig.adaptiveEnabled());
        } else {
            this.rateLimiter = null;
        }

        log.debug(() -> "BedrockEnhancedClient created [contextWindow=" + contextWindowConfig
                        + ", retryConfig=" + retryConfig
                        + ", throttlingConfig=" + throttlingConfig + "]");
    }

    @Override
    public ChatSession createSession(String modelId) {
        Validate.paramNotBlank(modelId, "modelId");
        return createSession(CreateSessionRequest.builder().modelId(modelId).build());
    }

    @Override
    public ChatSession createSession(CreateSessionRequest request) {
        Validate.paramNotNull(request, "request");

        ContextWindowConfig sessionContextConfig = request.contextWindowConfig() != null
                                                   ? request.contextWindowConfig()
                                                   : contextWindowConfig;

        ConversationSummarizer summarizer = null;
        if (sessionContextConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.SUMMARIZE
            || sessionContextConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_SUMMARIZE) {
            summarizer = buildSummarizer(request.modelId());
        }

        CostOptimizedContextManager costOptimizedManager = null;
        if (sessionContextConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_TRIMMING
            || sessionContextConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_SUMMARIZE) {

            ConversationSummarizer costOptSummarizer =
                sessionContextConfig.contextStrategy() == ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_SUMMARIZE
                    ? summarizer : null;

            // Token mode is preferred when both token thresholds are configured
            if (sessionContextConfig.targetRecentTokens() != null
                && sessionContextConfig.maxRecentTokens() != null) {
                costOptimizedManager = CostOptimizedContextManager.tokenMode(
                    sessionContextConfig.targetRecentTokens(),
                    sessionContextConfig.maxRecentTokens(),
                    sessionContextConfig.cacheReadCostRatio(),
                    sessionContextConfig.cacheWriteCostRatio(),
                    sessionContextConfig.expectedTotalTurns(),
                    costOptSummarizer);
            } else {
                // Turn mode: T = minMessages/2, M = maxMessages/2
                int t = sessionContextConfig.minMessages() / 2;
                int m = sessionContextConfig.maxMessages() / 2;
                if (t < 1) t = 1;
                if (m <= t) m = t + 1;
                costOptimizedManager = CostOptimizedContextManager.turnMode(
                    t, m,
                    sessionContextConfig.cacheReadCostRatio(),
                    sessionContextConfig.cacheWriteCostRatio(),
                    sessionContextConfig.expectedTotalTurns(),
                    costOptSummarizer);
            }
        }

        ContextWindowManager contextManager = new ContextWindowManager(sessionContextConfig, summarizer);
        BedrockRetryHandler retryHandler = new BedrockRetryHandler(retryConfig, rateLimiter);

        log.debug(() -> "Creating chat session [modelId=" + request.modelId()
                        + ", contextWindow=" + sessionContextConfig
                        + ", tokenBudget=" + request.tokenBudget() + "]");

        return new DefaultChatSession(
            bedrockRuntimeClient,
            request.modelId(),
            request.systemPrompts() != null ? request.systemPrompts() : Collections.emptyList(),
            request.inferenceConfig(),
            request.tokenBudget(),
            contextManager,
            costOptimizedManager,
            retryHandler,
            rateLimiter,
            promptCachingConfig,
            pricingProvider
        );
    }

    private ConversationSummarizer buildSummarizer(String modelId) {
        return messages -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Summarize the following conversation concisely, preserving key facts, ");
            sb.append("decisions, and context needed to continue the conversation:\n\n");
            for (Message msg : messages) {
                sb.append(msg.role().toString()).append(": ");
                if (msg.content() != null) {
                    for (ContentBlock block : msg.content()) {
                        if (block.text() != null) {
                            sb.append(block.text());
                        }
                    }
                }
                sb.append("\n");
            }

            ConverseRequest summarizeRequest = ConverseRequest.builder()
                .modelId(modelId)
                .messages(Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromText(sb.toString()))
                    .build())
                .system(SystemContentBlock.fromText(
                    "You are a conversation summarizer. Produce a brief, factual summary."))
                .build();

            ConverseResponse response = bedrockRuntimeClient.converse(summarizeRequest);
            if (response.output() != null && response.output().message() != null
                && response.output().message().content() != null
                && !response.output().message().content().isEmpty()) {
                return response.output().message().content().get(0).text();
            }
            return "Summary unavailable.";
        };
    }

    @Override
    public void close() {
        if (isDefaultClient) {
            bedrockRuntimeClient.close();
        }
    }

    public static Builder builder() {
        return new DefaultBuilder();
    }

    static final class DefaultBuilder implements Builder {
        private BedrockRuntimeClient bedrockRuntimeClient;
        private ContextWindowConfig contextWindowConfig;
        private ThrottlingConfig throttlingConfig;
        private BedrockRetryConfig retryConfig;
        private PromptCachingConfig promptCachingConfig;
        private PricingProvider pricingProvider;

        private DefaultBuilder() {
        }

        @Override
        public Builder bedrockRuntimeClient(BedrockRuntimeClient bedrockRuntimeClient) {
            this.bedrockRuntimeClient = bedrockRuntimeClient;
            return this;
        }

        @Override
        public Builder contextWindowConfig(ContextWindowConfig contextWindowConfig) {
            this.contextWindowConfig = contextWindowConfig;
            return this;
        }

        @Override
        public Builder throttlingConfig(ThrottlingConfig throttlingConfig) {
            this.throttlingConfig = throttlingConfig;
            return this;
        }

        @Override
        public Builder retryConfig(BedrockRetryConfig retryConfig) {
            this.retryConfig = retryConfig;
            return this;
        }

        @Override
        public Builder promptCachingConfig(PromptCachingConfig promptCachingConfig) {
            this.promptCachingConfig = promptCachingConfig;
            return this;
        }

        @Override
        public Builder pricingProvider(PricingProvider pricingProvider) {
            this.pricingProvider = pricingProvider;
            return this;
        }

        @Override
        public BedrockEnhancedClient build() {
            return new DefaultBedrockEnhancedClient(this);
        }
    }
}
