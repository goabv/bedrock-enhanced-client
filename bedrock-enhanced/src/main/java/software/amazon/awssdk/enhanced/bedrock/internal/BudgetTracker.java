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

package software.amazon.awssdk.enhanced.bedrock.internal;

import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.BudgetExceededException;
import software.amazon.awssdk.enhanced.bedrock.CostBudgetConfig;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;
import software.amazon.awssdk.utils.Logger;

/**
 * Tracks actual spend and applies budget rules per the cost budget spec.
 *
 * <p>Behavior depends on {@code expectedTotalTurns} and {@link CostBudgetConfig.Mode}:
 * <ul>
 *   <li>With expectedTotalTurns: forecast remaining spend and trigger early trims
 *       or fallback when projection exceeds budget.</li>
 *   <li>Without expectedTotalTurns: only run actual spend tracking and a per-request
 *       affordability check (no full forecast).</li>
 * </ul>
 */
@SdkInternalApi
public final class BudgetTracker {

    private static final Logger log = Logger.loggerFor(BudgetTracker.class);

    private final CostBudgetConfig config;
    private final PricingProvider pricingProvider;
    private final String modelId;
    private final Integer expectedTotalTurns; // optional

    private double spentSoFar;
    private long totalNewInputTokens;  // for averages
    private long totalOutputTokens;
    private int turns;

    public BudgetTracker(CostBudgetConfig config,
                         PricingProvider pricingProvider,
                         String modelId,
                         Integer expectedTotalTurns) {
        this.config = config;
        this.pricingProvider = pricingProvider;
        this.modelId = modelId;
        this.expectedTotalTurns = expectedTotalTurns;
    }

    /**
     * Records actual spend from the most recent response.
     */
    public void recordTurn(int newInputTokens, int cacheReadTokens, int cacheWriteTokens, int outputTokens) {
        if (pricingProvider == null) {
            return;
        }
        double inRate = pricingProvider.inputPricePer1K(modelId);
        double outRate = pricingProvider.outputPricePer1K(modelId);
        double crRate = pricingProvider.cacheReadPricePer1K(modelId);
        double cwRate = pricingProvider.cacheWritePricePer1K(modelId);

        double cost = (newInputTokens / 1000.0) * inRate
                    + (cacheReadTokens / 1000.0) * crRate
                    + (cacheWriteTokens / 1000.0) * cwRate
                    + (outputTokens / 1000.0) * outRate;
        spentSoFar += cost;
        totalNewInputTokens += newInputTokens;
        totalOutputTokens += outputTokens;
        turns++;
    }

    /**
     * Estimates the cost of the next request given the prefix that will be sent.
     * Uses observed averages for new input and output tokens.
     */
    public double estimatedNextCost(int prefixTokensCached, int prefixTokensToWrite) {
        if (pricingProvider == null) {
            return 0;
        }
        double inRate = pricingProvider.inputPricePer1K(modelId);
        double outRate = pricingProvider.outputPricePer1K(modelId);
        double crRate = pricingProvider.cacheReadPricePer1K(modelId);
        double cwRate = pricingProvider.cacheWritePricePer1K(modelId);

        int avgNewInput = turns > 0 ? (int) (totalNewInputTokens / turns) : 200;
        int avgOutput = turns > 0 ? (int) (totalOutputTokens / turns) : 300;

        return (prefixTokensCached / 1000.0) * crRate
             + (prefixTokensToWrite / 1000.0) * cwRate
             + (avgNewInput / 1000.0) * inRate
             + (avgOutput / 1000.0) * outRate;
    }

    /**
     * Pre-request affordability check. If the projected next-request cost would exceed budget,
     * returns the appropriate {@link Action}.
     *
     * @param canTrim         Whether the strategy supports trimming (false for Default/NONE).
     * @param prefixTokens    Estimated prefix tokens that will be sent (cached portion).
     * @param hasUnwrittenPrefix Whether some prefix tokens still need to be cache-written.
     */
    public Action checkBeforeRequest(boolean canTrim, int prefixTokens, boolean hasUnwrittenPrefix) {
        if (config.mode() == CostBudgetConfig.Mode.OFF) {
            return Action.PROCEED;
        }
        double estCachedRead = prefixTokens;
        double estWrite = hasUnwrittenPrefix ? prefixTokens : 0;
        double next = estimatedNextCost((int) estCachedRead, (int) estWrite);
        double remaining = config.budget() - spentSoFar;
        if (next <= remaining) {
            return Action.PROCEED;
        }
        log.warn(() -> "Budget pressure [spentSoFar=$" + String.format("%.4f", spentSoFar)
                       + ", nextEst=$" + String.format("%.4f", next)
                       + ", budget=$" + String.format("%.4f", config.budget()) + "]");
        if (config.mode() == CostBudgetConfig.Mode.WARN) {
            return Action.WARN;
        }
        // ENFORCE: try a trim first if we can; otherwise fail
        if (canTrim) {
            return Action.TRIM_AND_RECHECK;
        }
        return Action.FAIL;
    }

    /**
     * Re-check affordability after a corrective trim. If still unaffordable in ENFORCE mode,
     * throws {@link BudgetExceededException}.
     */
    public Action recheckAfterTrim(int prefixTokens, boolean hasUnwrittenPrefix) {
        if (config.mode() == CostBudgetConfig.Mode.OFF) {
            return Action.PROCEED;
        }
        double next = estimatedNextCost(prefixTokens, hasUnwrittenPrefix ? prefixTokens : 0);
        double remaining = config.budget() - spentSoFar;
        if (next <= remaining) {
            return Action.PROCEED;
        }
        if (config.mode() == CostBudgetConfig.Mode.WARN) {
            return Action.WARN;
        }
        throw BudgetExceededException.builder()
            .message("Budget exceeded even after trim [spentSoFar=$" + String.format("%.4f", spentSoFar)
                     + ", nextEst=$" + String.format("%.4f", next)
                     + ", budget=$" + String.format("%.4f", config.budget()) + "]")
            .spentSoFar(spentSoFar)
            .budget(config.budget())
            .estimatedNextCost(next)
            .build();
    }

    /**
     * Forecast-based decision when expectedTotalTurns is provided.
     * Computes remaining-spend forecast with and without an immediate trim.
     */
    public ForecastDecision evaluateForecast(int currentH, int currentT, int currentTurnCount) {
        if (expectedTotalTurns == null || pricingProvider == null) {
            return ForecastDecision.NO_ACTION;
        }
        int E = Math.max(expectedTotalTurns - currentTurnCount, 0);
        if (E == 0) return ForecastDecision.NO_ACTION;

        double inRate = pricingProvider.inputPricePer1K(modelId);
        double outRate = pricingProvider.outputPricePer1K(modelId);
        double crRate = pricingProvider.cacheReadPricePer1K(modelId);
        double cwRate = pricingProvider.cacheWritePricePer1K(modelId);

        double avgNewInput = turns > 0 ? (double) totalNewInputTokens / turns : 200;
        double avgOutput = turns > 0 ? (double) totalOutputTokens / turns : 300;

        // forecastNoTrim = Pcr * (E*H + A*E*(E-1)/2) + Pcw*A*E + Pout*O*E
        double forecastNoTrim = (crRate / 1000.0) * (E * currentH + avgNewInput * E * (E - 1) / 2.0)
                              + (cwRate / 1000.0) * (avgNewInput * E)
                              + (outRate / 1000.0) * (avgOutput * E);

        // forecastTrimNow = Pcw*T + Pcr*(E*T + A*E*(E-1)/2) + Pcw*A*E + Pout*O*E
        double forecastTrimNow = (cwRate / 1000.0) * currentT
                               + (crRate / 1000.0) * (E * currentT + avgNewInput * E * (E - 1) / 2.0)
                               + (cwRate / 1000.0) * (avgNewInput * E)
                               + (outRate / 1000.0) * (avgOutput * E);

        double remaining = config.budget() - spentSoFar;
        if (forecastNoTrim <= remaining) {
            return ForecastDecision.NO_ACTION;
        }
        if (forecastTrimNow <= remaining) {
            return ForecastDecision.TRIM_NOW;
        }
        return ForecastDecision.FALLBACK_REQUIRED;
    }

    public double spentSoFar() {
        return spentSoFar;
    }

    public double budget() {
        return config.budget();
    }

    public double remainingBudget() {
        return config.budget() - spentSoFar;
    }

    public CostBudgetConfig.Mode mode() {
        return config.mode();
    }

    public enum Action {
        /** No action — request is within budget. */
        PROCEED,
        /** WARN mode and budget exceeded — log a warning but continue. */
        WARN,
        /** ENFORCE mode and budget exceeded — try a trim and re-check. */
        TRIM_AND_RECHECK,
        /** ENFORCE mode and no trim possible — caller should fail. */
        FAIL
    }

    public enum ForecastDecision {
        NO_ACTION,
        TRIM_NOW,
        FALLBACK_REQUIRED
    }
}
