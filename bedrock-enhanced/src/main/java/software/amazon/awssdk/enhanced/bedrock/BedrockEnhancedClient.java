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

import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.annotations.ThreadSafe;
import software.amazon.awssdk.enhanced.bedrock.internal.DefaultBedrockEnhancedClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.utils.SdkAutoCloseable;

/**
 * A high-level client for Amazon Bedrock Runtime that provides conversation context window
 * management, token usage tracking, enhanced retry policies, and client-side throttling.
 *
 * <p>This client wraps the low-level {@link BedrockRuntimeClient} and adds:
 * <ul>
 *     <li>Automatic conversation history management with configurable context window limits</li>
 *     <li>Token usage tracking and optional budget enforcement</li>
 *     <li>Bedrock-specific retry policies with exponential backoff for throttling</li>
 *     <li>Client-side rate limiting to avoid server-side throttling</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * {@snippet :
 *     BedrockEnhancedClient client = BedrockEnhancedClient.builder()
 *         .bedrockRuntimeClient(bedrockRuntimeClient)
 *         .contextWindowConfig(c -> c.maxTokens(4096))
 *         .throttlingConfig(t -> t.maxRequestsPerSecond(5.0))
 *         .build();
 *
 *     ChatSession session = client.createSession("anthropic.claude-3-sonnet-20240229-v1:0");
 *     ChatResponse response = session.converse("Hello, how are you?");
 * }
 */
@SdkPublicApi
@ThreadSafe
public interface BedrockEnhancedClient extends SdkAutoCloseable {

    /**
     * Creates a new {@link ChatSession} for the specified model. The session maintains
     * conversation history and tracks token usage across multiple turns.
     *
     * @param modelId The model ID, inference profile ID, or ARN to use for inference.
     * @return A new chat session bound to this client and model.
     */
    ChatSession createSession(String modelId);

    /**
     * Creates a new {@link ChatSession} with custom session-level configuration.
     *
     * @param request The session creation request with configuration overrides.
     * @return A new chat session bound to this client and model.
     */
    ChatSession createSession(CreateSessionRequest request);

    /**
     * Creates a new {@link ChatSession} with custom session-level configuration.
     *
     * @param request A consumer to configure the session creation request.
     * @return A new chat session bound to this client and model.
     */
    default ChatSession createSession(Consumer<CreateSessionRequest.Builder> request) {
        CreateSessionRequest.Builder builder = CreateSessionRequest.builder();
        request.accept(builder);
        return createSession(builder.build());
    }

    /**
     * Creates a builder for configuring and creating a {@link BedrockEnhancedClient}.
     *
     * @return A new builder instance.
     */
    static Builder builder() {
        return DefaultBedrockEnhancedClient.builder();
    }

    /**
     * Creates a {@link BedrockEnhancedClient} with default settings using the default
     * {@link BedrockRuntimeClient}.
     *
     * @return A new client instance with default configuration.
     */
    static BedrockEnhancedClient create() {
        return builder().build();
    }

    /**
     * Builder for configuring and creating a {@link BedrockEnhancedClient}.
     */
    interface Builder {

        /**
         * Sets the underlying {@link BedrockRuntimeClient} to use for API calls.
         * If not specified, a default client will be created.
         *
         * @param bedrockRuntimeClient The low-level Bedrock Runtime client.
         * @return This builder for method chaining.
         */
        Builder bedrockRuntimeClient(BedrockRuntimeClient bedrockRuntimeClient);

        /**
         * Configures the context window management settings.
         *
         * @param contextWindowConfig The context window configuration.
         * @return This builder for method chaining.
         */
        Builder contextWindowConfig(ContextWindowConfig contextWindowConfig);

        /**
         * Configures the context window management settings using a builder consumer.
         *
         * @param contextWindowConfig A consumer to configure the context window.
         * @return This builder for method chaining.
         */
        default Builder contextWindowConfig(Consumer<ContextWindowConfig.Builder> contextWindowConfig) {
            ContextWindowConfig.Builder b = ContextWindowConfig.builder();
            contextWindowConfig.accept(b);
            return contextWindowConfig(b.build());
        }

        /**
         * Configures the client-side throttling settings.
         *
         * @param throttlingConfig The throttling configuration.
         * @return This builder for method chaining.
         */
        Builder throttlingConfig(ThrottlingConfig throttlingConfig);

        /**
         * Configures the client-side throttling settings using a builder consumer.
         *
         * @param throttlingConfig A consumer to configure throttling.
         * @return This builder for method chaining.
         */
        default Builder throttlingConfig(Consumer<ThrottlingConfig.Builder> throttlingConfig) {
            ThrottlingConfig.Builder b = ThrottlingConfig.builder();
            throttlingConfig.accept(b);
            return throttlingConfig(b.build());
        }

        /**
         * Configures the enhanced retry policy for Bedrock-specific errors.
         *
         * @param retryConfig The retry configuration.
         * @return This builder for method chaining.
         */
        Builder retryConfig(BedrockRetryConfig retryConfig);

        /**
         * Configures the enhanced retry policy using a builder consumer.
         *
         * @param retryConfig A consumer to configure retry behavior.
         * @return This builder for method chaining.
         */
        default Builder retryConfig(Consumer<BedrockRetryConfig.Builder> retryConfig) {
            BedrockRetryConfig.Builder b = BedrockRetryConfig.builder();
            retryConfig.accept(b);
            return retryConfig(b.build());
        }

        /**
         * Configures prompt caching. When enabled, cache checkpoints are automatically
         * inserted into conversation requests to reduce latency and token costs.
         * By default, prompt caching is disabled.
         *
         * @param promptCachingConfig The prompt caching configuration.
         * @return This builder for method chaining.
         */
        Builder promptCachingConfig(PromptCachingConfig promptCachingConfig);

        /**
         * Configures prompt caching using a builder consumer.
         *
         * @param promptCachingConfig A consumer to configure prompt caching.
         * @return This builder for method chaining.
         */
        default Builder promptCachingConfig(Consumer<PromptCachingConfig.Builder> promptCachingConfig) {
            PromptCachingConfig.Builder b = PromptCachingConfig.builder();
            promptCachingConfig.accept(b);
            return promptCachingConfig(b.build());
        }

        /**
         * Sets the pricing provider for cost estimation. By default, no pricing
         * provider is configured and {@link ChatSession#costEstimate()} returns
         * zero costs. Use {@link PricingProvider#api()} for live pricing from the
         * AWS Pricing API, or {@link PricingProvider#builtIn()} for a built-in
         * table that requires no API calls.
         *
         * @param pricingProvider The pricing provider.
         * @return This builder for method chaining.
         */
        Builder pricingProvider(PricingProvider pricingProvider);

        /**
         * Sets the optional conversation cost budget. Applies across all strategies
         * (including {@link ContextWindowConfig.ContextStrategy} = no trimming) and at the
         * session level. When unset or {@link CostBudgetConfig.Mode#OFF}, no budget logic runs.
         */
        Builder costBudgetConfig(CostBudgetConfig costBudgetConfig);

        /**
         * Sets the optional conversation cost budget using a builder consumer.
         */
        default Builder costBudgetConfig(Consumer<CostBudgetConfig.Builder> costBudgetConfig) {
            CostBudgetConfig.Builder b = CostBudgetConfig.builder();
            costBudgetConfig.accept(b);
            return costBudgetConfig(b.build());
        }

        /**
         * Builds the {@link BedrockEnhancedClient}.
         *
         * @return A configured client instance.
         */
        BedrockEnhancedClient build();
    }
}
