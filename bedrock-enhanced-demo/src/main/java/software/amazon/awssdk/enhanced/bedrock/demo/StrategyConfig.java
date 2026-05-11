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
 */
public class StrategyConfig {
    private String name;
    private String strategy; // NONE, SLIDING_WINDOW, COST_OPTIMIZED_TRIMMING, COST_OPTIMIZED_SUMMARIZE, SUMMARIZE
    private int maxTokens;   // 0 or Integer.MAX_VALUE = no cap
    private int coherenceFloor; // Cmin in user messages (trim target)
    private int maxMessages;    // Cmax in user messages (trim trigger), 0 = same as coherenceFloor
    private boolean cachingEnabled;

    public StrategyConfig() {
    }

    public StrategyConfig(String name, String strategy, int maxTokens, int coherenceFloor, int maxMessages, boolean cachingEnabled) {
        this.name = name;
        this.strategy = strategy;
        this.maxTokens = maxTokens;
        this.coherenceFloor = coherenceFloor;
        this.maxMessages = maxMessages;
        this.cachingEnabled = cachingEnabled;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public int getCoherenceFloor() { return coherenceFloor; }
    public void setCoherenceFloor(int coherenceFloor) { this.coherenceFloor = coherenceFloor; }
    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }
    public boolean isCachingEnabled() { return cachingEnabled; }
    public void setCachingEnabled(boolean cachingEnabled) { this.cachingEnabled = cachingEnabled; }
}
