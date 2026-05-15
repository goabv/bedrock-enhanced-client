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
 * Optional conversation cost budget that applies to any session, regardless of
 * the configured {@link ContextWindowConfig.ContextStrategy}.
 *
 * <p>The budget layer wraps the strategy's trim decisions. It can:
 * <ul>
 *     <li>Track actual spend after every response.</li>
 *     <li>Forecast remaining spend when {@code expectedTotalTurns} is provided.</li>
 *     <li>Trigger an earlier trim than the strategy would have (when the strategy supports it).</li>
 *     <li>Warn or enforce a stop when projected/actual spend exceeds the budget.</li>
 * </ul>
 *
 * <p>This is an orthogonal layer — a session can have a budget without a context
 * strategy that supports trimming (e.g., {@code Default}), in which case the budget
 * only emits warnings or throws to enforce.
 */
@SdkPublicApi
public final class CostBudgetConfig {

    private final double budget;
    private final Mode mode;

    private CostBudgetConfig(Builder builder) {
        if (builder.budget < 0) {
            throw new IllegalArgumentException("budget must be >= 0");
        }
        this.budget = builder.budget;
        this.mode = builder.mode != null ? builder.mode : Mode.OFF;
    }

    /** Maximum desired spend for the conversation, in USD. */
    public double budget() {
        return budget;
    }

    /** Budget enforcement mode. */
    public Mode mode() {
        return mode;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return ToString.builder("CostBudgetConfig")
                       .add("budget", String.format("$%.4f", budget))
                       .add("mode", mode)
                       .build();
    }

    /**
     * How the budget layer reacts when projected or actual spend exceeds the budget.
     */
    public enum Mode {
        /** Budget is tracked but no action is taken. Equivalent to having no budget. */
        OFF,

        /**
         * Track actual spend and emit warnings when projected or actual spend exceeds the
         * budget. Requests are still sent.
         */
        WARN,

        /**
         * Track actual spend, attempt corrective trims, and throw
         * {@link BudgetExceededException} when even after fallback the next request would
         * exceed the budget. The caller must decide how to handle.
         */
        ENFORCE
    }

    public static final class Builder {
        private double budget;
        private Mode mode = Mode.OFF;

        private Builder() {
        }

        /** Maximum desired spend for the conversation, in USD. Must be >= 0. */
        public Builder budget(double budget) {
            this.budget = budget;
            return this;
        }

        /** Budget enforcement mode. Defaults to {@link Mode#OFF}. */
        public Builder mode(Mode mode) {
            this.mode = mode;
            return this;
        }

        public CostBudgetConfig build() {
            return new CostBudgetConfig(this);
        }
    }
}
