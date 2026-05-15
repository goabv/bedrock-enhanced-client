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
 * Snapshot of the conversation cost budget for the current session. Returned by
 * {@link ChatSession#budgetStatus()} when a {@link CostBudgetConfig} has been
 * configured (and is not {@link CostBudgetConfig.Mode#OFF}); otherwise {@code null}.
 */
@SdkPublicApi
public final class BudgetStatus {

    private final double spentSoFar;
    private final double budget;
    private final double remaining;
    private final String mode;

    public BudgetStatus(double spentSoFar, double budget, double remaining, String mode) {
        this.spentSoFar = spentSoFar;
        this.budget = budget;
        this.remaining = remaining;
        this.mode = mode;
    }

    /** Cumulative actual spend so far, in USD. */
    public double spentSoFar() {
        return spentSoFar;
    }

    /** Configured budget, in USD. */
    public double budget() {
        return budget;
    }

    /** Remaining budget, in USD. May be negative if already exceeded. */
    public double remaining() {
        return remaining;
    }

    /** Active mode: OFF, WARN, or ENFORCE. */
    public String mode() {
        return mode;
    }

    /** Spent fraction in [0,1] (or higher if exceeded). */
    public double spentFraction() {
        if (budget <= 0) {
            return 0;
        }
        return spentSoFar / budget;
    }
}
