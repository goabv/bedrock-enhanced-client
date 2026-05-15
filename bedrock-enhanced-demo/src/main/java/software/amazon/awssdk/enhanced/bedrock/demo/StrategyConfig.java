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

package software.amazon.awssdk.enhanced.bedrock.demo;

/**
 * Configuration for a single strategy slot in the demo comparison.
 *
 * <p>For sliding window: {@code coherenceFloor} = Cmin, {@code maxMessages} = Cmax.
 * <p>For cost-optimized turn mode: {@code targetTurns} = T, {@code maxTurns} = M.
 * <p>For cost-optimized token mode: {@code targetRecentTokens} = T, {@code maxRecentTokens} = M.
 */
public class StrategyConfig {
    private String name;
    private String strategy;
    private int maxTokens;             // sliding window only
    private int coherenceFloor;        // sliding window: Cmin
    private int maxMessages;           // sliding window: Cmax
    private int targetTurns;           // cost-optimized turn mode: T
    private int maxTurns;              // cost-optimized turn mode: M
    private Integer targetRecentTokens; // cost-optimized token mode: T (preferred)
    private Integer maxRecentTokens;    // cost-optimized token mode: M (preferred)
    private boolean cachingEnabled;
    private Integer expectedTotalTurns;

    public StrategyConfig() {
    }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { this.strategy = v; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int v) { this.maxTokens = v; }
    public int getCoherenceFloor() { return coherenceFloor; }
    public void setCoherenceFloor(int v) { this.coherenceFloor = v; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int v) { this.maxMessages = v; }
    public int getTargetTurns() { return targetTurns; }
    public void setTargetTurns(int v) { this.targetTurns = v; }
    public int getMaxTurns() { return maxTurns; }
    public void setMaxTurns(int v) { this.maxTurns = v; }
    public Integer getTargetRecentTokens() { return targetRecentTokens; }
    public void setTargetRecentTokens(Integer v) { this.targetRecentTokens = v; }
    public Integer getMaxRecentTokens() { return maxRecentTokens; }
    public void setMaxRecentTokens(Integer v) { this.maxRecentTokens = v; }
    public boolean isCachingEnabled() { return cachingEnabled; }
    public void setCachingEnabled(boolean v) { this.cachingEnabled = v; }
    public Integer getExpectedTotalTurns() { return expectedTotalTurns; }
    public void setExpectedTotalTurns(Integer v) { this.expectedTotalTurns = v; }
}
