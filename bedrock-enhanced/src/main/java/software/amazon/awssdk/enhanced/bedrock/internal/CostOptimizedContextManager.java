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
 * Cost-optimized context window manager implementing Strategy C v2 (TARGET/MAX with
 * optional expectedTotalTurns).
 *
 * <p><b>Trim policy:</b>
 * <ul>
 *   <li>H = current retained recent-history turns</li>
 *   <li>T = targetRecentTurns (trim landing point)</li>
 *   <li>M = maxRecentTurns (hard trim trigger)</li>
 *   <li>If H >= M: must trim to T (reason: MAX_REACHED)</li>
 *   <li>If T &lt; H &lt; M and expectedTotalTurns is present: trim to T if
 *       E &gt; (W * T) / (R * (H - T)) (reason: EARLY_COST_JUSTIFIED)</li>
 *   <li>Otherwise: do not trim</li>
 * </ul>
 *
 * <p><b>Cache policy:</b>
 * <ul>
 *   <li>Cache checkpoint placed at the end of all active messages every turn.</li>
 *   <li>The accumulating tail is always cached so subsequent turns benefit from cache reads.</li>
 *   <li>After a trim, the retained TARGET window is re-cached as the new prefix.</li>
 * </ul>
 *
 * <p><b>Strategy-level break-even (for planning, NOT runtime decisions):</b>
 * {@code expectedTotalTurns > T + M + (2*W*T) / (R*(M-T))}
 */
@SdkInternalApi
public final class CostOptimizedContextManager {

    private static final Logger log = Logger.loggerFor(CostOptimizedContextManager.class);

    private static final int BEDROCK_MIN = 1024;

    // T - retained turns after trim (trim landing point)
    private final int targetRecentTurns;
    // M - hard trim trigger (must trim when H >= M)
    private final int maxRecentTurns;
    // R - cache read cost ratio
    private final double cacheReadCostRatio;
    // W - cache write cost ratio
    private final double cacheWriteCostRatio;
    // Optional expected total turns for early-trim cost decision
    private final Integer expectedTotalTurns;
    private final ConversationSummarizer summarizer;

    private final List<Message> history;
    private final List<Integer> tokenCounts;
    private int turnCount;
    private int trimCount;
    private int activeInputTokens;
    private TrimReason lastTrimReason;

    /**
     * Creates a new cost-optimized context manager (Strategy C v2).
     *
     * @param targetRecentTurns    T — number of recent turns to retain after each trim. Must be > 0.
     * @param maxRecentTurns       M — hard trim trigger. Must be > T.
     * @param cacheReadCostRatio   R — cache read cost / normal input cost. Must be > 0.
     * @param cacheWriteCostRatio  W — cache write cost / normal input cost. Must be >= 0.
     * @param expectedTotalTurns   Optional expected total turns for early-trim cost evaluation.
     *                             If null, only MAX trimming is performed.
     * @param summarizer           Optional summarizer. If non-null, dropped turns are summarized
     *                             into one message instead of dropped entirely.
     */
    public CostOptimizedContextManager(int targetRecentTurns,
                                       int maxRecentTurns,
                                       double cacheReadCostRatio,
                                       double cacheWriteCostRatio,
                                       Integer expectedTotalTurns,
                                       ConversationSummarizer summarizer) {
        if (targetRecentTurns <= 0) {
            throw new IllegalArgumentException("targetRecentTurns (T) must be > 0");
        }
        if (maxRecentTurns <= targetRecentTurns) {
            throw new IllegalArgumentException(
                "maxRecentTurns (M=" + maxRecentTurns + ") must be > targetRecentTurns (T=" + targetRecentTurns + ")");
        }
        if (cacheReadCostRatio <= 0) {
            throw new IllegalArgumentException("cacheReadCostRatio (R) must be > 0");
        }
        if (cacheWriteCostRatio < 0) {
            throw new IllegalArgumentException("cacheWriteCostRatio (W) must be >= 0");
        }
        this.targetRecentTurns = targetRecentTurns;
        this.maxRecentTurns = maxRecentTurns;
        this.cacheReadCostRatio = cacheReadCostRatio;
        this.cacheWriteCostRatio = cacheWriteCostRatio;
        this.expectedTotalTurns = expectedTotalTurns;
        this.summarizer = summarizer;
        this.history = new ArrayList<>();
        this.tokenCounts = new ArrayList<>();
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
            // Cache checkpoint at the end of the last message — caches everything before it
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
     * Decides whether to trim per spec Section 6.
     *
     * <pre>
     * H = current retained turns (= turnCount, which is the size of history we've kept)
     * If H >= M:           trim — reason MAX_REACHED
     * Elif T < H < M:
     *   If expectedTotalTurns and E > (W * T) / (R * (H - T)):
     *     trim — reason EARLY_COST_JUSTIFIED
     *   Else: no trim
     * Else (H <= T): no trim
     * </pre>
     */
    private TrimReason decideTrim() {
        int h = currentRetainedTurns();

        // H >= M: hard trim
        if (h >= maxRecentTurns) {
            return TrimReason.MAX_REACHED;
        }

        // T < H < M: consider early trim if expectedTotalTurns is present
        if (h > targetRecentTurns) {
            if (expectedTotalTurns == null) {
                return null; // no forecast — defer to MAX trim
            }
            int e = Math.max(expectedTotalTurns - turnCount, 0);
            if (e == 0) {
                return null;
            }
            double threshold = (cacheWriteCostRatio * targetRecentTurns)
                               / (cacheReadCostRatio * (h - targetRecentTurns));
            if (e > threshold) {
                return TrimReason.EARLY_COST_JUSTIFIED;
            }
        }
        return null;
    }

    private void performTrim(TrimReason reason) {
        int keepMessages = targetRecentTurns * 2;
        int startIdx = history.size() - keepMessages;
        if (startIdx < 0) {
            startIdx = 0;
        }
        if (startIdx == 0) {
            // Nothing to trim
            return;
        }

        if (summarizer != null) {
            // Summarize the older messages into one summary message
            List<Message> olderMessages = new ArrayList<>(history.subList(0, startIdx));
            String summary = summarizer.summarize(olderMessages);

            Message summaryMsg = Message.builder()
                .role(ConversationRole.ASSISTANT)
                .content(ContentBlock.fromText("[Context Summary] " + summary))
                .build();

            int summaryTokens = summary.length() / 4 + 10; // approximate

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
                        + ", summarized=" + (summarizer != null)
                        + ", T=" + targetRecentTurns
                        + ", M=" + maxRecentTurns
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

    /**
     * Returns the current retained recent-history size in turns (H).
     * Counts user/assistant pairs in the active history.
     */
    public int currentRetainedTurns() {
        return history.size() / 2;
    }

    /** Returns the full active history as an unmodifiable list. */
    public List<Message> history() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /** Returns the total token count of all messages currently in the active prompt. */
    public int totalHistoryTokens() {
        return activeInputTokens;
    }

    /** Returns the number of messages in the active history. */
    public int messageCount() {
        return history.size();
    }

    /** Returns the number of completed turns (cumulative, not retained). */
    public int turnCount() {
        return turnCount;
    }

    /** Returns the number of trims that have occurred. */
    public int trimCount() {
        return trimCount;
    }

    /** Returns the reason for the most recent trim, or null if no trim has occurred. */
    public TrimReason lastTrimReason() {
        return lastTrimReason;
    }

    /**
     * Strategy-level break-even threshold for planning purposes (NOT used at runtime).
     *
     * <p>Returns the expectedTotalTurns above which Strategy C is cheaper than never trimming.
     *
     * <p>Formula: T + M + (2*W*T) / (R*(M-T))
     */
    public double strategyBreakEvenThreshold() {
        return targetRecentTurns + maxRecentTurns
               + (2.0 * cacheWriteCostRatio * targetRecentTurns)
                 / (cacheReadCostRatio * (maxRecentTurns - targetRecentTurns));
    }

    /** Returns T (targetRecentTurns). */
    public int targetRecentTurns() {
        return targetRecentTurns;
    }

    /** Returns M (maxRecentTurns). */
    public int maxRecentTurns() {
        return maxRecentTurns;
    }

    /** Clears all state. */
    public void clear() {
        history.clear();
        tokenCounts.clear();
        turnCount = 0;
        trimCount = 0;
        activeInputTokens = 0;
        lastTrimReason = null;
    }

    /**
     * Reason for the most recent trim event. Useful for metrics/logging.
     */
    public enum TrimReason {
        /** H >= M, must trim regardless of cost math. */
        MAX_REACHED,
        /** T < H < M and cost math says early trim pays back (E > threshold). */
        EARLY_COST_JUSTIFIED,
        /** Trim fired manually (caller forced it). */
        FORCED
    }
}
