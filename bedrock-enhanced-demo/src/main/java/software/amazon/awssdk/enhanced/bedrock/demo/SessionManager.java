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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.enhanced.bedrock.BedrockEnhancedClient;
import software.amazon.awssdk.enhanced.bedrock.ChatSession;
import software.amazon.awssdk.enhanced.bedrock.ContextWindowConfig;
import software.amazon.awssdk.enhanced.bedrock.CreateSessionRequest;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;
import software.amazon.awssdk.enhanced.bedrock.PromptCachingConfig;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

/**
 * Dynamically creates and manages chat sessions based on user-selected strategy configurations.
 */
@Component
public class SessionManager {

    private static final String SYSTEM_PROMPT =
        "You are a knowledgeable assistant who is an expert on the Harry Potter book series by J.K. Rowling. "
        + "You can discuss characters, plot points, magical concepts, and themes in depth. "
        + "You also help with software development questions about AWS SDK for Java v2. "
        + "Keep responses under 150 words for quick demo purposes but be detailed and specific.";

    private final BedrockRuntimeClient runtimeClient;

    // Current active configuration
    private List<StrategyConfig> activeConfigs;
    private Map<String, ChatSession> activeSessions; // key = strategy name
    private Map<String, BedrockEnhancedClient> activeClients;
    private String activeModelId;

    public SessionManager(BedrockRuntimeClient runtimeClient) {
        this.runtimeClient = runtimeClient;
        this.activeConfigs = getDefaultConfigs();
        this.activeSessions = new LinkedHashMap<>();
        this.activeClients = new LinkedHashMap<>();
    }

    public static List<StrategyConfig> getDefaultConfigs() {
        return Arrays.asList(
            new StrategyConfig("Default", "NONE", 0, 0, 0, false),
            new StrategyConfig("Sliding Window", "SLIDING_WINDOW", 32768, 10, 10, false),
            new StrategyConfig("Cost Optimized (Caching + Trimming)", "COST_OPTIMIZED_TRIMMING", 0, 10, 10, true)
        );
    }

    public synchronized void applyConfig(List<StrategyConfig> configs, String modelId) {
        // Close old clients
        closeAll();

        this.activeConfigs = new ArrayList<>(configs);
        this.activeModelId = modelId;
        this.activeSessions = new LinkedHashMap<>();
        this.activeClients = new LinkedHashMap<>();

        for (StrategyConfig cfg : configs) {
            BedrockEnhancedClient client = buildClient(cfg);
            activeClients.put(cfg.getName(), client);

            ChatSession session = client.createSession(CreateSessionRequest.builder()
                .modelId(modelId)
                .addSystemPrompt(SystemContentBlock.fromText(SYSTEM_PROMPT))
                .inferenceConfig(InferenceConfiguration.builder()
                    .maxTokens(512)
                    .temperature(0.7f)
                    .build())
                .build());
            activeSessions.put(cfg.getName(), session);
        }
    }

    public synchronized Map<String, ChatSession> getSessions() {
        return activeSessions;
    }

    public synchronized List<StrategyConfig> getActiveConfigs() {
        return activeConfigs;
    }

    public synchronized String getActiveModelId() {
        return activeModelId;
    }

    public synchronized void ensureInitialized(String modelId) {
        if (activeSessions.isEmpty()) {
            applyConfig(activeConfigs, modelId);
        }
    }

    public synchronized void resetAll() {
        activeSessions.values().forEach(ChatSession::reset);
        closeAll();
        activeSessions = new LinkedHashMap<>();
        activeClients = new LinkedHashMap<>();
    }

    private void closeAll() {
        activeClients.values().forEach(BedrockEnhancedClient::close);
        activeClients.clear();
    }

    private BedrockEnhancedClient buildClient(StrategyConfig cfg) {
        System.out.println("Building client for: " + cfg.getName() + " strategy=" + cfg.getStrategy());
        BedrockEnhancedClient.Builder builder = BedrockEnhancedClient.builder()
            .bedrockRuntimeClient(runtimeClient)
            .pricingProvider(PricingProvider.builtIn());

        // Prompt caching
        builder.promptCachingConfig(PromptCachingConfig.builder()
            .enabled(cfg.isCachingEnabled())
            .cacheSystemPrompt(cfg.isCachingEnabled())
            .build());

        // Context window config based on strategy
        String strategy = cfg.getStrategy();
        if ("NONE".equals(strategy)) {
            // No context management — effectively unlimited
            builder.contextWindowConfig(ContextWindowConfig.builder()
                .maxTokens(Integer.MAX_VALUE)
                .maxMessages(Integer.MAX_VALUE)
                .build());
        } else {
            int maxTokens = cfg.getMaxTokens() > 0 ? cfg.getMaxTokens() : Integer.MAX_VALUE;
            // Cmin in total messages (user + assistant pairs)
            int cMin = cfg.getCoherenceFloor() > 0 ? cfg.getCoherenceFloor() * 2 : Integer.MAX_VALUE;
            // Cmax in total messages — defaults to Cmin if not set or 0
            int cMax = cfg.getMaxMessages() > 0 ? cfg.getMaxMessages() * 2 : cMin;

            ContextWindowConfig.ContextStrategy contextStrategy;
            switch (strategy) {
                case "SLIDING_WINDOW":
                    contextStrategy = ContextWindowConfig.ContextStrategy.SLIDING_WINDOW;
                    break;
                case "SUMMARIZE":
                    contextStrategy = ContextWindowConfig.ContextStrategy.SUMMARIZE;
                    break;
                case "COST_OPTIMIZED_TRIMMING":
                    contextStrategy = ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_TRIMMING;
                    break;
                case "COST_OPTIMIZED_SUMMARIZE":
                    contextStrategy = ContextWindowConfig.ContextStrategy.COST_OPTIMIZED_SUMMARIZE;
                    break;
                default:
                    contextStrategy = ContextWindowConfig.ContextStrategy.SLIDING_WINDOW;
            }

            // For cost-optimized strategies, C is passed via maxMessages (coherenceFloor = maxMessages/2 internally)
            // For sliding window, use Cmin/Cmax semantics
            if ("COST_OPTIMIZED_TRIMMING".equals(strategy) || "COST_OPTIMIZED_SUMMARIZE".equals(strategy)) {
                int c = cfg.getCoherenceFloor() > 0 ? cfg.getCoherenceFloor() * 2 : 20;
                builder.contextWindowConfig(ContextWindowConfig.builder()
                    .maxTokens(maxTokens)
                    .maxMessages(c)
                    .contextStrategy(contextStrategy)
                    .build());
            } else {
                builder.contextWindowConfig(ContextWindowConfig.builder()
                    .maxTokens(maxTokens)
                    .maxMessages(cMax)
                    .minMessages(cMin)
                    .contextStrategy(contextStrategy)
                    .build());
            }
        }

        return builder.build();
    }
}
