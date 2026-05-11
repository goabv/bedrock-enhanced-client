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

import java.util.List;
import java.util.function.Consumer;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * A stateful conversation session that manages message history, token tracking,
 * and context window trimming across multiple conversation turns.
 *
 * <p>Each session is bound to a specific model and maintains its own conversation
 * history. Sessions are not thread-safe; use one session per thread or synchronize
 * externally.
 *
 * <h2>Usage Example</h2>
 * {@snippet :
 *     ChatSession session = client.createSession(
 *         CreateSessionRequest.builder()
 *             .modelId("anthropic.claude-3-sonnet-20240229-v1:0")
 *             .addSystemPrompt(SystemContentBlock.builder()
 *                 .text("You are a helpful assistant.")
 *                 .build())
 *             .inferenceConfig(InferenceConfiguration.builder()
 *                 .maxTokens(1024)
 *                 .temperature(0.7f)
 *                 .build())
 *             .tokenBudget(100000)
 *             .build());
 *
 *     ChatResponse response = session.converse("Tell me about Java.");
 *     ChatResponse followUp = session.converse("Can you give me an example?");
 *
 *     TokenUsageSummary usage = session.tokenUsage();
 * }
 */
@SdkPublicApi
public interface ChatSession {

    /**
     * Sends a text message and returns the model's response. The message and response
     * are automatically added to the conversation history.
     *
     * @param userMessage The user's text message.
     * @return The model's response with token usage metadata.
     * @throws TokenBudgetExceededException if the session's token budget is exceeded.
     */
    ChatResponse converse(String userMessage);

    /**
     * Sends a message with structured content blocks and returns the model's response.
     *
     * @param content The content blocks to send (text, images, documents, etc.).
     * @return The model's response with token usage metadata.
     * @throws TokenBudgetExceededException if the session's token budget is exceeded.
     */
    ChatResponse converse(List<ContentBlock> content);

    /**
     * Sends a fully customized converse request. The session's conversation history,
     * system prompts, and inference config are merged with the request. The request's
     * modelId is ignored in favor of the session's model.
     *
     * @param requestOverride A consumer to customize the converse request.
     * @return The model's response with token usage metadata.
     * @throws TokenBudgetExceededException if the session's token budget is exceeded.
     */
    ChatResponse converse(Consumer<ConverseRequest.Builder> requestOverride);

    /**
     * Returns the current conversation history as an unmodifiable list.
     */
    List<Message> conversationHistory();

    /**
     * Returns the cumulative token usage for this session.
     */
    TokenUsageSummary tokenUsage();

    /**
     * Returns the estimated running cost for this session based on token usage
     * and model pricing. Returns zero costs if pricing data is not available
     * for the model.
     */
    CostEstimate costEstimate();

    /**
     * Returns the model ID this session is bound to.
     */
    String modelId();

    /**
     * Returns the current estimated token count of the context window (conversation
     * history only, not cumulative session total).
     */
    int contextWindowTokenCount();

    /**
     * Returns the number of times summarization has been triggered in this session.
     */
    int summarizationCount();

    /**
     * Clears the conversation history and resets token tracking for this session.
     */
    void reset();
}
