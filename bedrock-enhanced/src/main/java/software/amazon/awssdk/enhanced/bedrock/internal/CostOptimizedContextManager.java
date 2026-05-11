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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.utils.Logger;

/**
 * Cost-optimized context window manager that coordinates trimming with prompt caching.
 *
 * <p>Uses a threshold-based trim strategy derived from cache pricing to minimize
 * average input token cost per turn. Trims in bulk (down to C turns) and freezes
 * the remaining turns as a cacheable prefix ("frozen floor").
 *
 * <p>Key formula: T = 2 * S_eff * (alpha - beta), where:
 * <ul>
 *   <li>S_eff = S_static + F (effective cached prefix size)</li>
 *   <li>alpha = cache write multiplier (1.25 for 5min TTL, 2.0 for 1hr TTL)</li>
 *   <li>beta = cache read multiplier (0.10)</li>
 * </ul>
 *
 * <p>Trimming fires when accumulated (uncached) tokens exceed T AND history
 * has more than C turns.
 */
@SdkInternalApi
public final class CostOptimizedContextManager {

    private static final Logger log = Logger.loggerFor(CostOptimizedContextManager.class);

    private static final double ALPHA_5MIN = 1.25;
    private static final double ALPHA_1HOUR = 2.00;
    private static final double BETA = 0.10;
    private static final int BEDROCK_MIN = 1024;

    private final int sStatic;
    private final int coherenceFloor;
    private final double alpha;
    private final ConversationSummarizer summarizer;

    private final List<Message> history;
    private final List<Integer> tokenCounts;
    private List<Message> frozenFloor;
    private Set<Integer> frozenFloorIndices;
    private int frozenTokenCount;
    private int sEff;
    private int trimThreshold;
    private int trimCount;

    /**
     * Creates a new cost-optimized context manager.
     *
     * @param sStatic Token count of static prefix (system prompt). 0 if none.
     * @param coherenceFloor Minimum turns retained after trim (C).
     * @param useOneHourTtl Whether to use 1-hour TTL pricing (only for Claude 4.5 models).
     * @param summarizer Optional summarizer. If non-null, trim uses summarization instead of dropping.
     */
    public CostOptimizedContextManager(int sStatic, int coherenceFloor, boolean useOneHourTtl,
                                       ConversationSummarizer summarizer) {
        this.sStatic = sStatic;
        this.coherenceFloor = coherenceFloor;
        this.alpha = useOneHourTtl ? ALPHA_1HOUR : ALPHA_5MIN;
        this.summarizer = summarizer;
        this.history = new ArrayList<>();
        this.tokenCounts = new ArrayList<>();
        this.frozenFloor = new ArrayList<>();
        this.frozenFloorIndices = new HashSet<>();
        this.frozenTokenCount = 0;
        this.sEff = sStatic;
        this.trimThreshold = computeThreshold(sStatic);
        this.trimCount = 0;
    }

    /**
     * Adds a user+assistant turn to history with exact token counts.
     */
    public void addTurn(Message userMessage, int userTokens,
                        Message assistantMessage, int assistantTokens) {
        history.add(userMessage);
        tokenCounts.add(userTokens);
        history.add(assistantMessage);
        tokenCounts.add(assistantTokens);
    }

    /**
     * Returns whether a trim should fire based on accumulated tokens exceeding
     * the threshold AND having more than C turns (2*C messages) in history.
     */
    public boolean shouldTrim() {
        if (history.size() <= coherenceFloor * 2) {
            return false;
        }
        return accumulatedTokens() > trimThreshold;
    }

    /**
     * Returns whether the cache marker should be applied.
     * Before first trim: cache if total history tokens >= BEDROCK_MIN.
     * After first trim: cache if S_eff >= BEDROCK_MIN.
     */
    public boolean shouldCache() {
        if (frozenFloor.isEmpty()) {
            // Before first trim: cache the whole history as provisional prefix
            return totalHistoryTokens() >= BEDROCK_MIN;
        }
        return sEff >= BEDROCK_MIN;
    }

    /**
     * Trims history. If a summarizer is configured, summarizes older messages into one
     * and keeps the last C turns after it. Otherwise, drops older messages and keeps last C turns.
     */
    public void trim() {
        int keepMessages = coherenceFloor * 2;
        int startIdx = history.size() - keepMessages;
        if (startIdx < 0) {
            startIdx = 0;
        }

        List<Message> recentHistory;
        List<Integer> recentTokens;

        if (summarizer != null && startIdx > 0) {
            // Summarize the older messages
            List<Message> olderMessages = new ArrayList<>(history.subList(0, startIdx));
            String summary = summarizer.summarize(olderMessages);

            // Build new history: [summary_as_assistant_msg] + recent messages
            Message summaryMsg = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText("[Context Summary] " + summary))
                .build();

            recentHistory = new ArrayList<>();
            recentHistory.add(summaryMsg);
            recentHistory.addAll(history.subList(startIdx, history.size()));

            // Estimate summary token count (~1 token per 4 chars)
            int summaryTokens = summary.length() / 4 + 10;
            recentTokens = new ArrayList<>();
            recentTokens.add(summaryTokens);
            recentTokens.addAll(tokenCounts.subList(startIdx, tokenCounts.size()));
        } else {
            recentHistory = new ArrayList<>(history.subList(startIdx, history.size()));
            recentTokens = new ArrayList<>(tokenCounts.subList(startIdx, tokenCounts.size()));
        }

        history.clear();
        history.addAll(recentHistory);
        tokenCounts.clear();
        tokenCounts.addAll(recentTokens);

        // Freeze the entire remaining history as the new floor
        frozenFloor = new ArrayList<>(history);
        frozenFloorIndices = new HashSet<>();
        frozenTokenCount = 0;
        for (int i = 0; i < history.size(); i++) {
            frozenFloorIndices.add(i);
            frozenTokenCount += tokenCounts.get(i);
        }

        sEff = sStatic + frozenTokenCount;
        trimThreshold = computeThreshold(sEff);
        trimCount++;

        log.debug(() -> "Trim fired [trimCount=" + trimCount
                        + ", summarized=" + (summarizer != null)
                        + ", historySize=" + history.size()
                        + ", F=" + frozenTokenCount
                        + ", S_eff=" + sEff
                        + ", T=" + trimThreshold + "]");
    }

    /**
     * Builds the message list for a Bedrock request, injecting cache markers
     * on frozen floor messages when caching is active. Before first trim,
     * places checkpoint on the last assistant message (provisional caching).
     */
    public List<Message> buildMessages() {
        if (shouldTrim()) {
            trim();
        }

        boolean useCache = shouldCache();
        List<Message> messages = new ArrayList<>();

        if (frozenFloor.isEmpty() && useCache) {
            // Before first trim: place checkpoint on last assistant message
            int lastAssistantIdx = -1;
            for (int i = history.size() - 1; i >= 0; i--) {
                if (ConversationRole.ASSISTANT.equals(history.get(i).role())) {
                    lastAssistantIdx = i;
                    break;
                }
            }
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                if (i == lastAssistantIdx) {
                    List<ContentBlock> newContent = new ArrayList<>(msg.content());
                    newContent.add(ContentBlock.fromCachePoint(
                        CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
                    messages.add(msg.toBuilder().content(newContent).build());
                } else {
                    messages.add(msg);
                }
            }
        } else {
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                boolean isFrozen = frozenFloorIndices.contains(i);

                if (isFrozen && useCache && i == lastFrozenIndex()) {
                    List<ContentBlock> newContent = new ArrayList<>(msg.content());
                    newContent.add(ContentBlock.fromCachePoint(
                        CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
                    messages.add(msg.toBuilder().content(newContent).build());
                } else {
                    messages.add(msg);
                }
            }
        }

        return messages;
    }

    /**
     * Returns the full history as an unmodifiable list.
     */
    public List<Message> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Returns the current accumulated (uncached) token count.
     */
    public int accumulatedTokens() {
        int total = 0;
        for (int i = 0; i < tokenCounts.size(); i++) {
            if (!frozenFloorIndices.contains(i)) {
                total += tokenCounts.get(i);
            }
        }
        return total;
    }

    /**
     * Returns the frozen floor token count (F).
     */
    public int frozenTokenCount() {
        return frozenTokenCount;
    }

    /**
     * Returns the effective cached prefix size (S_eff = S_static + F).
     */
    public int effectiveCachedSize() {
        return sEff;
    }

    /**
     * Returns the current trim threshold (T).
     */
    public int trimThreshold() {
        return trimThreshold;
    }

    /**
     * Returns the total token count of all messages in history.
     */
    public int totalHistoryTokens() {
        int total = 0;
        for (int tc : tokenCounts) {
            total += tc;
        }
        return total;
    }

    /**
     * Returns the number of messages in history.
     */
    public int messageCount() {
        return history.size();
    }

    /**
     * Returns the number of trims that have occurred.
     */
    public int trimCount() {
        return trimCount;
    }

    /**
     * Clears all state.
     */
    public void clear() {
        history.clear();
        tokenCounts.clear();
        frozenFloor.clear();
        frozenFloorIndices.clear();
        frozenTokenCount = 0;
        sEff = sStatic;
        trimThreshold = computeThreshold(sStatic);
        trimCount = 0;
    }

    private int computeThreshold(int effectiveSize) {
        return (int) (2.0 * effectiveSize * (alpha - BETA));
    }

    private int lastFrozenIndex() {
        int last = -1;
        for (int idx : frozenFloorIndices) {
            if (idx > last) {
                last = idx;
            }
        }
        return last;
    }
}
