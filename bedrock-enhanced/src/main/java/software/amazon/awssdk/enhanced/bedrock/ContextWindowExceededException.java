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
import software.amazon.awssdk.core.exception.SdkClientException;

/**
 * Thrown when a chat session's context window exceeds the configured limits and the
 * overflow policy is set to {@link ContextWindowConfig.OverflowPolicy#REJECT}.
 */
@SdkPublicApi
public final class ContextWindowExceededException extends SdkClientException {

    private static final long serialVersionUID = 1L;

    private final int currentTokens;
    private final int maxTokens;
    private final int currentMessages;
    private final int maxMessages;

    private ContextWindowExceededException(Builder builder) {
        super(builder);
        this.currentTokens = builder.currentTokens;
        this.maxTokens = builder.maxTokens;
        this.currentMessages = builder.currentMessages;
        this.maxMessages = builder.maxMessages;
    }

    /**
     * The estimated token count at the time the limit was hit.
     */
    public int currentTokens() {
        return currentTokens;
    }

    /**
     * The configured maximum token limit.
     */
    public int maxTokens() {
        return maxTokens;
    }

    /**
     * The current message count at the time the limit was hit.
     */
    public int currentMessages() {
        return currentMessages;
    }

    /**
     * The configured maximum message limit.
     */
    public int maxMessages() {
        return maxMessages;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BuilderImpl {
        private int currentTokens;
        private int maxTokens;
        private int currentMessages;
        private int maxMessages;

        private Builder() {
        }

        public Builder currentTokens(int currentTokens) {
            this.currentTokens = currentTokens;
            return this;
        }

        public Builder maxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder currentMessages(int currentMessages) {
            this.currentMessages = currentMessages;
            return this;
        }

        public Builder maxMessages(int maxMessages) {
            this.maxMessages = maxMessages;
            return this;
        }

        @Override
        public Builder message(String message) {
            super.message(message);
            return this;
        }

        @Override
        public Builder cause(Throwable cause) {
            super.cause(cause);
            return this;
        }

        @Override
        public ContextWindowExceededException build() {
            return new ContextWindowExceededException(this);
        }
    }
}
