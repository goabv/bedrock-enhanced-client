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
 * Thrown when a chat session's cumulative token usage exceeds the configured token budget.
 */
@SdkPublicApi
public final class TokenBudgetExceededException extends SdkClientException {

    private static final long serialVersionUID = 1L;

    private final long currentUsage;
    private final int budget;

    private TokenBudgetExceededException(Builder builder) {
        super(builder);
        this.currentUsage = builder.currentUsage;
        this.budget = builder.budget;
    }

    /**
     * The current total token usage at the time the budget was exceeded.
     */
    public long currentUsage() {
        return currentUsage;
    }

    /**
     * The configured token budget that was exceeded.
     */
    public int budget() {
        return budget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder extends BuilderImpl {
        private long currentUsage;
        private int budget;

        private Builder() {
        }

        public Builder currentUsage(long currentUsage) {
            this.currentUsage = currentUsage;
            return this;
        }

        public Builder budget(int budget) {
            this.budget = budget;
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
        public TokenBudgetExceededException build() {
            return new TokenBudgetExceededException(this);
        }
    }
}
