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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.enhanced.bedrock.BudgetStatus;
import software.amazon.awssdk.enhanced.bedrock.ChatResponse;
import software.amazon.awssdk.enhanced.bedrock.ChatSession;
import software.amazon.awssdk.enhanced.bedrock.CostEstimate;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final SessionManager sessionManager;
    private final List<TurnMetrics> metricsHistory = Collections.synchronizedList(new ArrayList<>());

    public ChatController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * Apply a new strategy configuration. Resets all sessions.
     */
    @PostMapping("/config")
    public ResponseEntity<String> applyConfig(@RequestBody ConfigRequest request) {
        try {
            sessionManager.applyConfig(request.getStrategies(), request.getModelId());
            metricsHistory.clear();
            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            String msg = e.getClass().getName() + ": " + e.getMessage();
            Throwable cause = e.getCause();
            while (cause != null) {
                msg += " | Caused by: " + cause.getClass().getName() + ": " + cause.getMessage();
                cause = cause.getCause();
            }
            return ResponseEntity.ok("ERROR: " + msg);
        }
    }

    /**
     * Get current active configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<ConfigResponse> getConfig() {
        ConfigResponse resp = new ConfigResponse();
        resp.setStrategies(sessionManager.getActiveConfigs());
        resp.setModelId(sessionManager.getActiveModelId());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/chat")
    public ResponseEntity<ComparisonResponse> chat(@RequestBody ChatRequest request) {
        String modelId = request.getModelId();
        String message = request.getMessage();

        sessionManager.ensureInitialized(modelId);
        Map<String, ChatSession> sessions = sessionManager.getSessions();

        if (sessions.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(ComparisonResponse.error("No strategies configured"));
        }

        // Run all strategies in parallel
        Map<String, CompletableFuture<ChatResponse>> futures = new LinkedHashMap<>();
        for (Map.Entry<String, ChatSession> entry : sessions.entrySet()) {
            String name = entry.getKey();
            ChatSession session = entry.getValue();
            futures.put(name, CompletableFuture.supplyAsync(() -> session.converse(message)));
        }

        try {
            Map<String, ChatResponse> responses = new LinkedHashMap<>();
            for (Map.Entry<String, CompletableFuture<ChatResponse>> entry : futures.entrySet()) {
                responses.put(entry.getKey(), entry.getValue().get());
            }

            ComparisonResponse comparison = buildComparison(sessions, responses);
            return ResponseEntity.ok(comparison);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().build();
        } catch (ExecutionException e) {
            return ResponseEntity.internalServerError()
                .body(ComparisonResponse.error(e.getCause().getMessage()));
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        sessionManager.resetAll();
        metricsHistory.clear();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/metrics")
    public ResponseEntity<List<TurnMetrics>> getMetrics() {
        return ResponseEntity.ok(new ArrayList<>(metricsHistory));
    }

    private ComparisonResponse buildComparison(Map<String, ChatSession> sessions,
                                               Map<String, ChatResponse> responses) {
        ComparisonResponse resp = new ComparisonResponse();
        Map<String, StrategyResult> results = new LinkedHashMap<>();

        int turnCount = 0;
        for (Map.Entry<String, ChatResponse> entry : responses.entrySet()) {
            String name = entry.getKey();
            ChatResponse chatResp = entry.getValue();
            ChatSession session = sessions.get(name);
            CostEstimate cost = session.costEstimate();

            StrategyResult result = new StrategyResult();
            result.setName(name);
            result.setResponseText(chatResp.text());
            result.setInputTokens(cost.inputTokens());
            result.setOutputTokens(cost.outputTokens());
            result.setCacheReadTokens(cost.cacheReadTokens());
            result.setCacheWriteTokens(cost.cacheWriteTokens());
            result.setInputCost(cost.inputCost());
            result.setOutputCost(cost.outputCost());
            result.setTotalCost(cost.totalCost());
            result.setCacheReadSavings(cost.cacheReadSavings());
            result.setContextWindowTokens(session.contextWindowTokenCount());
            result.setMessageCount(chatResp.conversationMessageCount());

            // Optional budget snapshot
            BudgetStatus bs = session.budgetStatus();
            if (bs != null) {
                result.setBudget(bs.budget());
                result.setBudgetSpent(bs.spentSoFar());
                result.setBudgetRemaining(bs.remaining());
                result.setBudgetMode(bs.mode());
            }

            results.put(name, result);
            turnCount = chatResp.sessionTokenUsage().turnCount();
        }

        resp.setResults(results);
        resp.setTurnCount(turnCount);

        // Store metrics
        TurnMetrics turn = new TurnMetrics();
        turn.setTurn(turnCount);
        turn.setResults(new LinkedHashMap<>(results));
        metricsHistory.add(turn);

        return resp;
    }

    // --- DTOs ---

    public static class ConfigRequest {
        private List<StrategyConfig> strategies;
        private String modelId;

        public List<StrategyConfig> getStrategies() { return strategies; }
        public void setStrategies(List<StrategyConfig> v) { this.strategies = v; }
        public String getModelId() { return modelId; }
        public void setModelId(String v) { this.modelId = v; }
    }

    public static class ConfigResponse {
        private List<StrategyConfig> strategies;
        private String modelId;

        public List<StrategyConfig> getStrategies() { return strategies; }
        public void setStrategies(List<StrategyConfig> v) { this.strategies = v; }
        public String getModelId() { return modelId; }
        public void setModelId(String v) { this.modelId = v; }
    }

    public static class ChatRequest {
        private String modelId;
        private String message;

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }
    }

    public static class ComparisonResponse {
        private Map<String, StrategyResult> results;
        private int turnCount;
        private String error;

        public static ComparisonResponse error(String msg) {
            ComparisonResponse r = new ComparisonResponse();
            r.error = msg;
            return r;
        }

        public Map<String, StrategyResult> getResults() { return results; }
        public void setResults(Map<String, StrategyResult> v) { this.results = v; }
        public int getTurnCount() { return turnCount; }
        public void setTurnCount(int v) { this.turnCount = v; }
        public String getError() { return error; }
        public void setError(String v) { this.error = v; }
    }

    public static class StrategyResult {
        private String name;
        private String responseText;
        private long inputTokens;
        private long outputTokens;
        private long cacheReadTokens;
        private long cacheWriteTokens;
        private double inputCost;
        private double outputCost;
        private double totalCost;
        private double cacheReadSavings;
        private int contextWindowTokens;
        private int messageCount;
        private Double budget;
        private Double budgetSpent;
        private Double budgetRemaining;
        private String budgetMode;

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getResponseText() { return responseText; }
        public void setResponseText(String v) { this.responseText = v; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long v) { this.inputTokens = v; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long v) { this.outputTokens = v; }
        public long getCacheReadTokens() { return cacheReadTokens; }
        public void setCacheReadTokens(long v) { this.cacheReadTokens = v; }
        public long getCacheWriteTokens() { return cacheWriteTokens; }
        public void setCacheWriteTokens(long v) { this.cacheWriteTokens = v; }
        public double getInputCost() { return inputCost; }
        public void setInputCost(double v) { this.inputCost = v; }
        public double getOutputCost() { return outputCost; }
        public void setOutputCost(double v) { this.outputCost = v; }
        public double getTotalCost() { return totalCost; }
        public void setTotalCost(double v) { this.totalCost = v; }
        public double getCacheReadSavings() { return cacheReadSavings; }
        public void setCacheReadSavings(double v) { this.cacheReadSavings = v; }
        public int getContextWindowTokens() { return contextWindowTokens; }
        public void setContextWindowTokens(int v) { this.contextWindowTokens = v; }
        public int getMessageCount() { return messageCount; }
        public void setMessageCount(int v) { this.messageCount = v; }
        public Double getBudget() { return budget; }
        public void setBudget(Double v) { this.budget = v; }
        public Double getBudgetSpent() { return budgetSpent; }
        public void setBudgetSpent(Double v) { this.budgetSpent = v; }
        public Double getBudgetRemaining() { return budgetRemaining; }
        public void setBudgetRemaining(Double v) { this.budgetRemaining = v; }
        public String getBudgetMode() { return budgetMode; }
        public void setBudgetMode(String v) { this.budgetMode = v; }
    }

    public static class TurnMetrics {
        private int turn;
        private Map<String, StrategyResult> results;

        public int getTurn() { return turn; }
        public void setTurn(int v) { this.turn = v; }
        public Map<String, StrategyResult> getResults() { return results; }
        public void setResults(Map<String, StrategyResult> v) { this.results = v; }
    }
}
