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
import java.util.List;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointBlock;
import software.amazon.awssdk.services.bedrockruntime.model.CachePointType;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.utils.Logger;

/**
 * Cost-optimized context window manager implementing Strategy C (TARGET/MAX with optional
 * expectedTotalTurns). Supports both TOKEN_MODE (preferred) and TURN_MODE (fallback).
 *
 * <p><b>Mode selection:</b>
 * <ul>
 *   <li>TOKEN_MODE: when both targetRecentTokens and maxRecentTokens are provided.</li>
 *   <li>TURN_MODE: when only targetRecentTurns and maxRecentTurns are provided.</li>
 *   <li>If both are provided, token mode is authoritative.</li>
 * </ul>
 *
 * <p><b>Trim policy (in active unit):</b>
 * <ul>
 *   <li>H = current retained recent-history size (in active unit)</li>
 *   <li>T = TARGET, M = MAX</li>
 *   <li>If H >= M: trim to T (reason: MAX_REACHED)</li>
 *   <li>If T &lt; H &lt; M and expectedTotalTurns is present and
 *       E &gt; (W * T) / (R * (H - T)): trim to T (reason: COST_BASED_EARLY_TRIM)</li>
 *   <li>Otherwise: do not trim</li>
 * </ul>
 *
 * <p><b>Cache policy:</b> Cache checkpoint placed at end of all active messages every turn.
 * The accumulating tail is always cached.
 */
@SdkInternalApi
public final class CostOptimizedContextManager {

    private static final Logger log = Logger.loggerFor(CostOptimizedContextManager.class);

    private static final int BEDROCK_MIN = 1024;

    private final PolicyUnit unit;
    private final int target;            // TARGET in active unit
    private final int max;               // MAX in active unit
    private final double cacheReadCostRatio;
    private final double cacheWriteCostRatio;
    private final Integer expectedTotalTurns;
    private final ConversationSummarizer summarizer;

    private final List<Message> history;
    private final List<Integer> tokenCounts;
    private int turnCount;
    private int trimCount;
    private int activeInputTokens;
    private TrimReason lastTrimReason;

    private CostOptimizedContextManager(PolicyUnit unit, int target, int max,
                                        double cacheReadCostRatio,
                                        double cacheWriteCostRatio,
                                        Integer expectedTotalTurns,
                                        ConversationSummarizer summarizer) {
        if (target <= 0) {
            throw new IllegalArgumentException("TARGET must be > 0");
        }
        if (max <= target) {
            throw new IllegalArgumentException("MAX (" + max + ") must be > TARGET (" + target + ")");
        }
        if (cacheReadCostRatio <= 0) {
            throw new IllegalArgumentException("cacheReadCostRatio must be > 0");
        }
        if (cacheWriteCostRatio < 0) {
            throw new IllegalArgumentException("cacheWriteCostRatio must be >= 0");
        }
        this.unit = unit;
        this.target = target;
        this.max = max;
        this.cacheReadCostRatio = cacheReadCostRatio;
        this.cacheWriteCostRatio = cacheWriteCostRatio;
        this.expectedTotalTurns = expectedTotalTurns;
        this.summarizer = summarizer;
        this.history = new ArrayList<>();
        this.tokenCounts = new ArrayList<>();
    }

    /**
     * Creates a manager in TOKEN_MODE.
     *
     * @param targetTokens TARGET in tokens (must be > 0).
     * @param maxTokens    MAX in tokens (must be > targetTokens).
     */
    public static CostOptimizedContextManager tokenMode(int targetTokens, int maxTokens,
                                                        double cacheReadCostRatio,
                                                        double cacheWriteCostRatio,
                                                        Integer expectedTotalTurns,
                                                        ConversationSummarizer summarizer) {
        return new CostOptimizedContextManager(PolicyUnit.TOKENS, targetTokens, maxTokens,
            cacheReadCostRatio, cacheWriteCostRatio, expectedTotalTurns, summarizer);
    }

    /**
     * Creates a manager in TURN_MODE.
     *
     * @param targetTurns TARGET in turns (must be > 0).
     * @param maxTurns    MAX in turns (must be > targetTurns).
     */
    public static CostOptimizedContextManager turnMode(int targetTurns, int maxTurns,
                                                       double cacheReadCostRatio,
                                                       double cacheWriteCostRatio,
                                                       Integer expectedTotalTurns,
                                                       ConversationSummarizer summarizer) {
        return new CostOptimizedContextManager(PolicyUnit.TURNS, targetTurns, maxTurns,
            cacheReadCostRatio, cacheWriteCostRatio, expectedTotalTurns, summarizer);
    }

    /**
     * Adds a completed turn (user + assistant) with token counts to history.
     */
    public void addTurn(Message userMessage, int userTokens,
                        Message assistantMessage, int assistantTokens) {
        history.add(userMessage);
        tokenCounts.add(userTokens);
        history.add(assistantMessage);
        tokenCounts.add(assistantTokens);
        turnCount++;
        recomputeActiveInputTokens();
    }

    /**
     * Builds the message list for the next request. Applies trim if due, then places
     * a cache checkpoint at the end of all active messages.
     */
    public List<Message> buildMessages() {
        TrimReason reason = decideTrim();
        if (reason != null) {
            performTrim(reason);
        }

        List<Message> result = new ArrayList<>(history);
        if (!result.isEmpty() && totalHistoryTokens() >= BEDROCK_MIN) {
            int lastIdx = result.size() - 1;
            Message last = result.get(lastIdx);
            List<ContentBlock> newContent = new ArrayList<>(last.content());
            newContent.add(ContentBlock.fromCachePoint(
                CachePointBlock.builder().type(CachePointType.DEFAULT).build()));
            result.set(lastIdx, last.toBuilder().content(newContent).build());
        }
        return result;
    }

    /**
     * Decides whether to trim per spec. H is measured in the active unit.
     */
    private TrimReason decideTrim() {
        int h = currentRetainedSize();

        if (h >= max) {
            return TrimReason.MAX_REACHED;
        }

        if (h > target) {
            if (expectedTotalTurns == null) {
                return null;
            }
            int e = Math.max(expectedTotalTurns - turnCount, 0);
            if (e == 0) {
                return null;
            }
            double threshold = (cacheWriteCostRatio * target)
                               / (cacheReadCostRatio * (h - target));
            if (e > threshold) {
                return TrimReason.COST_BASED_EARLY_TRIM;
            }
        }
        return null;
    }

    /**
     * Returns H in the active unit.
     */
    public int currentRetainedSize() {
        if (unit == PolicyUnit.TOKENS) {
            return activeInputTokens;
        }
        return history.size() / 2; // turns
    }

    /**
     * Performs the trim down to TARGET in the active unit.
     */
    private void performTrim(TrimReason reason) {
        int startIdx;
        if (unit == PolicyUnit.TOKENS) {
            // Drop oldest pairs until total tokens <= target
            // Walk from the end, accumulating tokens, find the cutoff index
            int retainedTokens = 0;
            int cutoff = history.size(); // cutoff = first kept index
            // Iterate by pairs (user+assistant) from the end
            for (int i = history.size() - 2; i >= 0; i -= 2) {
                int pairTokens = tokenCounts.get(i) + tokenCounts.get(i + 1);
                if (retainedTokens + pairTokens > target) {
                    break;
                }
                retainedTokens += pairTokens;
                cutoff = i;
            }
            // If we couldn't even fit one pair, keep the most recent pair anyway
            if (cutoff == history.size() && history.size() >= 2) {
                cutoff = history.size() - 2;
            }
            startIdx = cutoff;
        } else {
            // TURN mode: keep last `target` turns = target * 2 messages
            int keepMessages = target * 2;
            startIdx = history.size() - keepMessages;
            if (startIdx < 0) {
                startIdx = 0;
            }
        }

        if (startIdx <= 0) {
            return; // nothing to drop
        }

        if (summarizer != null) {
            // Summarize the older messages into one summary message
            List<Message> olderMessages = new ArrayList<>(history.subList(0, startIdx));
            String summary = summarizer.summarize(olderMessages);

            Message summaryMsg = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText("[Context Summary] " + summary))
                .build();

            int summaryTokens = summary.length() / 4 + 10;

            List<Message> newHistory = new ArrayList<>();
            List<Integer> newTokens = new ArrayList<>();
            newHistory.add(summaryMsg);
            newTokens.add(summaryTokens);
            newHistory.addAll(history.subList(startIdx, history.size()));
            newTokens.addAll(tokenCounts.subList(startIdx, tokenCounts.size()));

            history.clear();
            history.addAll(newHistory);
            tokenCounts.clear();
            tokenCounts.addAll(newTokens);
        } else {
            List<Message> kept = new ArrayList<>(history.subList(startIdx, history.size()));
            List<Integer> keptTokens = new ArrayList<>(tokenCounts.subList(startIdx, tokenCounts.size()));
            history.clear();
            history.addAll(kept);
            tokenCounts.clear();
            tokenCounts.addAll(keptTokens);
        }

        trimCount++;
        lastTrimReason = reason;
        recomputeActiveInputTokens();

        log.debug(() -> "Trim fired [reason=" + reason
                        + ", trimCount=" + trimCount
                        + ", unit=" + unit
                        + ", T=" + target
                        + ", M=" + max
                        + ", summarized=" + (summarizer != null)
                        + ", activeMessages=" + history.size()
                        + ", activeTokens=" + activeInputTokens + "]");
    }

    private void recomputeActiveInputTokens() {
        int total = 0;
        for (int tc : tokenCounts) {
            total += tc;
        }
        activeInputTokens = total;
    }

    /** Returns the active policy unit. */
    public PolicyUnit unit() {
        return unit;
    }

    /** Returns TARGET in the active unit. */
    public int target() {
        return target;
    }

    /** Returns MAX in the active unit. */
    public int max() {
        return max;
    }

    public List<Message> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    public int totalHistoryTokens() {
        return activeInputTokens;
    }

    public int messageCount() {
        return history.size();
    }

    public int turnCount() {
        return turnCount;
    }

    public int trimCount() {
        return trimCount;
    }

    public TrimReason lastTrimReason() {
        return lastTrimReason;
    }

    /**
     * Strategy-level break-even threshold in the active unit (for planning, NOT runtime).
     * Formula: T + M + (2*W*T) / (R*(M-T))
     */
    public double strategyBreakEvenThreshold() {
        return target + max + (2.0 * cacheWriteCostRatio * target)
                              / (cacheReadCostRatio * (max - target));
    }

    public void clear() {
        history.clear();
        tokenCounts.clear();
        turnCount = 0;
        trimCount = 0;
        activeInputTokens = 0;
        lastTrimReason = null;
    }

    /**
     * Reason for the most recent trim event.
     */
    public enum TrimReason {
        MAX_REACHED,
        COST_BASED_EARLY_TRIM,
        FORCED
    }

    /**
     * Active policy unit — TOKENS (preferred) or TURNS (fallback).
     */
    public enum PolicyUnit {
        TOKENS,
        TURNS
    }
}
