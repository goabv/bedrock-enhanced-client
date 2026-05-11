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
 * Cumulative token usage summary for a chat session, tracking total input and output
 * tokens across all conversation turns.
 */
@SdkPublicApi
public final class TokenUsageSummary {

    private final long totalInputTokens;
    private final long totalOutputTokens;
    private final long totalTokens;
    private final int turnCount;

    public TokenUsageSummary(long totalInputTokens, long totalOutputTokens, int turnCount) {
        this.totalInputTokens = totalInputTokens;
        this.totalOutputTokens = totalOutputTokens;
        this.totalTokens = totalInputTokens + totalOutputTokens;
        this.turnCount = turnCount;
    }

    /**
     * Total input tokens consumed across all turns in the session.
     */
    public long totalInputTokens() {
        return totalInputTokens;
    }

    /**
     * Total output tokens generated across all turns in the session.
     */
    public long totalOutputTokens() {
        return totalOutputTokens;
    }

    /**
     * Total tokens (input + output) consumed across all turns.
     */
    public long totalTokens() {
        return totalTokens;
    }

    /**
     * The number of conversation turns completed in this session.
     */
    public int turnCount() {
        return turnCount;
    }

    @Override
    public String toString() {
        return ToString.builder("TokenUsageSummary")
                       .add("totalInputTokens", totalInputTokens)
                       .add("totalOutputTokens", totalOutputTokens)
                       .add("totalTokens", totalTokens)
                       .add("turnCount", turnCount)
                       .build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TokenUsageSummary that = (TokenUsageSummary) o;
        return totalInputTokens == that.totalInputTokens
               && totalOutputTokens == that.totalOutputTokens
               && turnCount == that.turnCount;
    }

    @Override
    public int hashCode() {
        int result = Long.hashCode(totalInputTokens);
        result = 31 * result + Long.hashCode(totalOutputTokens);
        result = 31 * result + turnCount;
        return result;
    }
}
