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

/**
 * Thrown by the budget layer when the next request would exceed the configured
 * {@link CostBudgetConfig} and the mode is {@link CostBudgetConfig.Mode#ENFORCE}.
 *
 * <p>The session does not retry or trim further on its own when this is thrown.
 * The caller is expected to handle the situation (reset the session, increase the
 * budget, switch to a cheaper model, etc.).
 */
@SdkPublicApi
public final class BudgetExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final double spentSoFar;
    private final double budget;
    private final double estimatedNextCost;

    private BudgetExceededException(String message, double spentSoFar, double budget,
                                    double estimatedNextCost) {
        super(message);
        this.spentSoFar = spentSoFar;
        this.budget = budget;
        this.estimatedNextCost = estimatedNextCost;
    }

    /** Cumulative spend so far, in USD. */
    public double spentSoFar() {
        return spentSoFar;
    }

    /** Configured budget, in USD. */
    public double budget() {
        return budget;
    }

    /** Estimated cost of the next request that triggered the exception, in USD. */
    public double estimatedNextCost() {
        return estimatedNextCost;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String message;
        private double spentSoFar;
        private double budget;
        private double estimatedNextCost;

        private Builder() {
        }

        public Builder message(String message) { this.message = message; return this; }
        public Builder spentSoFar(double v) { this.spentSoFar = v; return this; }
        public Builder budget(double v) { this.budget = v; return this; }
        public Builder estimatedNextCost(double v) { this.estimatedNextCost = v; return this; }

        public BudgetExceededException build() {
            return new BudgetExceededException(message, spentSoFar, budget, estimatedNextCost);
        }
    }
}
