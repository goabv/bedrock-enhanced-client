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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.ChatResponse;
import software.amazon.awssdk.enhanced.bedrock.ChatSession;
import software.amazon.awssdk.enhanced.bedrock.CostEstimate;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;
import software.amazon.awssdk.enhanced.bedrock.PromptCachingConfig;
import software.amazon.awssdk.enhanced.bedrock.TokenBudgetExceededException;
import software.amazon.awssdk.enhanced.bedrock.TokenUsageSummary;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.utils.Logger;

/**
 * Default implementation of {@link ChatSession} that manages conversation state,
 * token tracking, and delegates to the retry handler and rate limiter.
 */
@SdkInternalApi
public final class DefaultChatSession implements ChatSession {

    private static final Logger log = Logger.loggerFor(DefaultChatSession.class);

    private final BedrockRuntimeClient client;
    private final String modelId;
    private final List<SystemContentBlock> systemPrompts;
    private final InferenceConfiguration inferenceConfig;
    private final Integer tokenBudget;
    private final ContextWindowManager contextWindowManager;
    private final CostOptimizedContextManager costOptimizedManager;
    private final BedrockRetryHandler retryHandler;
    private final TokenBucketRateLimiter rateLimiter;
    private final PromptCachingConfig promptCachingConfig;
    private final PricingProvider pricingProvider;

    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalCacheReadTokens;
    private long totalCacheWriteTokens;
    private int turnCount;

    DefaultChatSession(BedrockRuntimeClient client,
                       String modelId,
                       List<SystemContentBlock> systemPrompts,
                       InferenceConfiguration inferenceConfig,
                       Integer tokenBudget,
                       ContextWindowManager contextWindowManager,
                       CostOptimizedContextManager costOptimizedManager,
                       BedrockRetryHandler retryHandler,
                       TokenBucketRateLimiter rateLimiter,
                       PromptCachingConfig promptCachingConfig,
                       PricingProvider pricingProvider) {
        this.client = client;
        this.modelId = modelId;
        this.systemPrompts = systemPrompts;
        this.inferenceConfig = inferenceConfig;
        this.tokenBudget = tokenBudget;
        this.contextWindowManager = contextWindowManager;
        this.costOptimizedManager = costOptimizedManager;
        this.retryHandler = retryHandler;
        this.rateLimiter = rateLimiter;
        this.promptCachingConfig = promptCachingConfig;
        this.pricingProvider = pricingProvider;
    }

    @Override
    public ChatResponse converse(String userMessage) {
        return converse(Collections.singletonList(ContentBlock.fromText(userMessage)));
    }

    @Override
    public ChatResponse converse(List<ContentBlock> content) {
        Message userMessage = Message.builder()
                                     .role(ConversationRole.USER)
                                     .content(content)
                                     .build();
        if (costOptimizedManager == null) {
            contextWindowManager.addMessage(userMessage);
        }
        return doConverse(null, userMessage);
    }

    @Override
    public ChatResponse converse(Consumer<ConverseRequest.Builder> requestOverride) {
        return doConverse(requestOverride, null);
    }

    @Override
    public List<Message> conversationHistory() {
        if (costOptimizedManager != null) {
            return costOptimizedManager.history();
        }
        return contextWindowManager.messages();
    }

    @Override
    public TokenUsageSummary tokenUsage() {
        return new TokenUsageSummary(totalInputTokens, totalOutputTokens, turnCount);
    }

    @Override
    public CostEstimate costEstimate() {
        if (pricingProvider == null) {
            return CostEstimate.builder()
                .inputTokens(totalInputTokens)
                .outputTokens(totalOutputTokens)
                .cacheReadTokens(totalCacheReadTokens)
                .cacheWriteTokens(totalCacheWriteTokens)
                .modelId(modelId)
                .build();
        }

        double inputRate = pricingProvider.inputPricePer1K(modelId);
        double outputRate = pricingProvider.outputPricePer1K(modelId);
        double cacheReadRate = pricingProvider.cacheReadPricePer1K(modelId);
        double cacheWriteRate = pricingProvider.cacheWritePricePer1K(modelId);

        long nonCachedInputTokens = totalInputTokens - totalCacheReadTokens;
        double inputCost = (nonCachedInputTokens / 1000.0) * inputRate
                         + (totalCacheReadTokens / 1000.0) * cacheReadRate
                         + (totalCacheWriteTokens / 1000.0) * cacheWriteRate;
        double outputCost = (totalOutputTokens / 1000.0) * outputRate;
        double cacheReadSavings = (totalCacheReadTokens / 1000.0) * (inputRate - cacheReadRate);

        return CostEstimate.builder()
            .inputCost(inputCost)
            .outputCost(outputCost)
            .totalCost(inputCost + outputCost)
            .cacheReadSavings(cacheReadSavings)
            .inputTokens(totalInputTokens)
            .outputTokens(totalOutputTokens)
            .cacheReadTokens(totalCacheReadTokens)
            .cacheWriteTokens(totalCacheWriteTokens)
            .modelId(modelId)
            .build();
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int contextWindowTokenCount() {
        if (costOptimizedManager != null) {
            return costOptimizedManager.totalHistoryTokens();
        }
        return contextWindowManager.displayTokenCount();
    }

    @Override
    public int summarizationCount() {
        if (costOptimizedManager != null) {
            return costOptimizedManager.trimCount();
        }
        return contextWindowManager.summarizationCount();
    }

    @Override
    public void reset() {
        contextWindowManager.clear();
        if (costOptimizedManager != null) {
            costOptimizedManager.clear();
        }
        totalInputTokens = 0;
        totalOutputTokens = 0;
        totalCacheReadTokens = 0;
        totalCacheWriteTokens = 0;
        turnCount = 0;
        log.debug(() -> "Session reset [modelId=" + modelId + "]");
    }

    private ChatResponse doConverse(Consumer<ConverseRequest.Builder> requestOverride, Message pendingUserMessage) {
        checkTokenBudget();

        if (rateLimiter != null) {
            rateLimiter.acquire();
        }

        List<Message> messages;
        if (costOptimizedManager != null) {
            // Cost-optimized mode: manager handles trimming and cache markers
            messages = costOptimizedManager.buildMessages();
            // Append the pending user message (not yet in history)
            if (pendingUserMessage != null) {
                messages = new ArrayList<>(messages);
                messages.add(pendingUserMessage);
            }
        } else {
            messages = contextWindowManager.messages();
            // Inject cache checkpoints if prompt caching is enabled
            if (promptCachingConfig != null && promptCachingConfig.enabled()) {
                messages = injectCacheCheckpoints(messages);
            }
        }

        ConverseRequest.Builder requestBuilder = ConverseRequest.builder()
            .modelId(modelId)
            .messages(messages);

        if (systemPrompts != null && !systemPrompts.isEmpty()) {
            if (promptCachingConfig != null && promptCachingConfig.enabled()
                && promptCachingConfig.cacheSystemPrompt()) {
                // Append a cache checkpoint after the system prompts
                List<SystemContentBlock> cachedSystem = new ArrayList<>(systemPrompts);
                cachedSystem.add(SystemContentBlock.fromCachePoint(
                    CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
                requestBuilder.system(cachedSystem);
            } else {
                requestBuilder.system(systemPrompts);
            }
        }
        if (inferenceConfig != null) {
            requestBuilder.inferenceConfig(inferenceConfig);
        }

        // Allow caller to customize additional fields (toolConfig, guardrailConfig, etc.)
        // but always use the managed messages and modelId
        if (requestOverride != null) {
            requestOverride.accept(requestBuilder);
            requestBuilder.modelId(modelId);
            requestBuilder.messages(messages);
        }

        ConverseRequest finalRequest = requestBuilder.build();

        ConverseResponse response = retryHandler.executeWithRetry(() -> client.converse(finalRequest));

        // Add assistant response to conversation history
        if (response.output() != null && response.output().message() != null) {
            if (costOptimizedManager != null) {
                // In cost-optimized mode, add the full turn (user + assistant) with token counts
                TokenUsage usage = response.usage();
                int inputTokens = usage != null ? usage.inputTokens() : 0;
                int outputTokens = usage != null ? usage.outputTokens() : 0;
                if (pendingUserMessage != null) {
                    costOptimizedManager.addTurn(pendingUserMessage, inputTokens,
                                                 response.output().message(), outputTokens);
                }
            } else {
                contextWindowManager.addMessage(response.output().message());
            }
        }

        // Update token tracking
        TokenUsage usage = response.usage();
        if (usage != null) {
            int cacheRead = usage.cacheReadInputTokens() != null ? usage.cacheReadInputTokens() : 0;
            int cacheWrite = usage.cacheWriteInputTokens() != null ? usage.cacheWriteInputTokens() : 0;
            int fullInput = usage.inputTokens() + cacheRead + cacheWrite;
            totalInputTokens += fullInput;
            totalOutputTokens += usage.outputTokens();
            if (usage.cacheReadInputTokens() != null) {
                totalCacheReadTokens += usage.cacheReadInputTokens();
            }
            if (usage.cacheWriteInputTokens() != null) {
                totalCacheWriteTokens += usage.cacheWriteInputTokens();
            }
            // Context window for display = all input tokens sent this turn
            // For trimming, include output since response is already in history
            if (costOptimizedManager == null) {
                contextWindowManager.updateTokenCountAndTrim(fullInput, usage.outputTokens());
            }
        }
        turnCount++;

        TokenUsageSummary sessionUsage = new TokenUsageSummary(totalInputTokens, totalOutputTokens, turnCount);

        log.debug(() -> "Converse completed [turn=" + turnCount
                        + ", messageCount=" + contextWindowManager.messageCount()
                        + ", sessionTokens=" + sessionUsage.totalTokens() + "]");

        return new ChatResponse(response, sessionUsage,
            costOptimizedManager != null ? costOptimizedManager.messageCount()
                                        : contextWindowManager.messageCount());
    }

    private void checkTokenBudget() {
        if (tokenBudget != null) {
            long currentTotal = totalInputTokens + totalOutputTokens;
            if (currentTotal >= tokenBudget) {
                throw TokenBudgetExceededException.builder()
                    .message("Token budget exceeded [currentUsage=" + currentTotal
                             + ", budget=" + tokenBudget + "]")
                    .currentUsage(currentTotal)
                    .budget(tokenBudget)
                    .build();
            }
        }
    }

    /**
     * Injects CachePointBlock content blocks into the message list based on the
     * caching strategy. For CHECKPOINT_EVERY_TURN, a cache checkpoint is appended
     * to the last assistant message's content blocks (the end of the previous turn).
     * This marks the conversation prefix as cacheable.
     */
    private List<Message> injectCacheCheckpoints(List<Message> original) {
        if (promptCachingConfig.cachingStrategy() == PromptCachingConfig.CachingStrategy.SYSTEM_PROMPT_ONLY) {
            return original;
        }

        // CHECKPOINT_EVERY_TURN: find the last assistant message and append a cache point
        int lastAssistantIdx = -1;
        for (int i = original.size() - 1; i >= 0; i--) {
            if (ConversationRole.ASSISTANT.equals(original.get(i).role())) {
                lastAssistantIdx = i;
                break;
            }
        }

        if (lastAssistantIdx < 0) {
            return original;
        }

        List<Message> result = new ArrayList<>(original);
        Message assistantMsg = result.get(lastAssistantIdx);

        // Append a CachePointBlock to the assistant message's content
        List<ContentBlock> newContent = new ArrayList<>(assistantMsg.content());
        newContent.add(ContentBlock.fromCachePoint(
            CachePointBlock.builder().type(CachePointType.DEFAULT).build()));

        Message updatedMsg = assistantMsg.toBuilder()
            .content(newContent)
            .build();
        result.set(lastAssistantIdx, updatedMsg);

        return result;
    }
}
