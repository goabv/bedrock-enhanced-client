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
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.utils.ToString;

/**
 * Response from a conversation turn, wrapping the low-level {@link ConverseResponse}
 * with additional session-level metadata.
 */
@SdkPublicApi
public final class ChatResponse {

    private final ConverseResponse converseResponse;
    private final TokenUsageSummary sessionTokenUsage;
    private final int conversationMessageCount;

    public ChatResponse(ConverseResponse converseResponse,
                        TokenUsageSummary sessionTokenUsage,
                        int conversationMessageCount) {
        this.converseResponse = converseResponse;
        this.sessionTokenUsage = sessionTokenUsage;
        this.conversationMessageCount = conversationMessageCount;
    }

    /**
     * The underlying Bedrock Runtime Converse response.
     */
    public ConverseResponse converseResponse() {
        return converseResponse;
    }

    /**
     * The text content of the assistant's response, extracted for convenience.
     * Returns an empty string if no text content is present.
     */
    public String text() {
        if (converseResponse.output() == null || converseResponse.output().message() == null
            || converseResponse.output().message().content() == null
            || converseResponse.output().message().content().isEmpty()) {
            return "";
        }
        return converseResponse.output().message().content().stream()
                               .filter(block -> block.text() != null)
                               .map(block -> block.text())
                               .findFirst()
                               .orElse("");
    }

    /**
     * The stop reason for this turn.
     */
    public StopReason stopReason() {
        return converseResponse.stopReason();
    }

    /**
     * Token usage for this individual turn.
     */
    public TokenUsage turnTokenUsage() {
        return converseResponse.usage();
    }

    /**
     * Cumulative token usage across the entire session.
     */
    public TokenUsageSummary sessionTokenUsage() {
        return sessionTokenUsage;
    }

    /**
     * The number of messages currently in the conversation history.
     */
    public int conversationMessageCount() {
        return conversationMessageCount;
    }

    @Override
    public String toString() {
        return ToString.builder("ChatResponse")
                       .add("stopReason", stopReason())
                       .add("conversationMessageCount", conversationMessageCount)
                       .add("sessionTokenUsage", sessionTokenUsage)
                       .build();
    }
}
