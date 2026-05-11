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
 * Running cost estimate for a chat session based on token usage and model pricing.
 */
@SdkPublicApi
public final class CostEstimate {

    private final double inputCost;
    private final double outputCost;
    private final double totalCost;
    private final double cacheReadSavings;
    private final long inputTokens;
    private final long outputTokens;
    private final long cacheReadTokens;
    private final long cacheWriteTokens;
    private final String modelId;

    CostEstimate(Builder builder) {
        this.inputCost = builder.inputCost;
        this.outputCost = builder.outputCost;
        this.totalCost = builder.totalCost;
        this.cacheReadSavings = builder.cacheReadSavings;
        this.inputTokens = builder.inputTokens;
        this.outputTokens = builder.outputTokens;
        this.cacheReadTokens = builder.cacheReadTokens;
        this.cacheWriteTokens = builder.cacheWriteTokens;
        this.modelId = builder.modelId;
    }

    /** Estimated cost for input tokens in USD. */
    public double inputCost() {
        return inputCost;
    }

    /** Estimated cost for output tokens in USD. */
    public double outputCost() {
        return outputCost;
    }

    /** Total estimated cost (input + output) in USD. */
    public double totalCost() {
        return totalCost;
    }

    /** Estimated savings from cache reads in USD. */
    public double cacheReadSavings() {
        return cacheReadSavings;
    }

    /** Total input tokens used. */
    public long inputTokens() {
        return inputTokens;
    }

    /** Total output tokens used. */
    public long outputTokens() {
        return outputTokens;
    }

    /** Tokens read from cache. */
    public long cacheReadTokens() {
        return cacheReadTokens;
    }

    /** Tokens written to cache. */
    public long cacheWriteTokens() {
        return cacheWriteTokens;
    }

    /** The model ID used for pricing. */
    public String modelId() {
        return modelId;
    }

    @Override
    public String toString() {
        return ToString.builder("CostEstimate")
                       .add("inputCost", String.format("$%.6f", inputCost))
                       .add("outputCost", String.format("$%.6f", outputCost))
                       .add("totalCost", String.format("$%.6f", totalCost))
                       .add("cacheReadSavings", String.format("$%.6f", cacheReadSavings))
                       .add("modelId", modelId)
                       .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private double inputCost;
        private double outputCost;
        private double totalCost;
        private double cacheReadSavings;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private String modelId;

        public Builder inputCost(double v) {
            this.inputCost = v;
            return this;
        }

        public Builder outputCost(double v) {
            this.outputCost = v;
            return this;
        }

        public Builder totalCost(double v) {
            this.totalCost = v;
            return this;
        }

        public Builder cacheReadSavings(double v) {
            this.cacheReadSavings = v;
            return this;
        }

        public Builder inputTokens(long v) {
            this.inputTokens = v;
            return this;
        }

        public Builder outputTokens(long v) {
            this.outputTokens = v;
            return this;
        }

        public Builder cacheReadTokens(long v) {
            this.cacheReadTokens = v;
            return this;
        }

        public Builder cacheWriteTokens(long v) {
            this.cacheWriteTokens = v;
            return this;
        }

        public Builder modelId(String v) {
            this.modelId = v;
            return this;
        }

        public CostEstimate build() {
            return new CostEstimate(this);
        }
    }
}
