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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.ContextWindowConfig;
import software.amazon.awssdk.enhanced.bedrock.ContextWindowExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.utils.Logger;

/**
 * Manages conversation history with support for multiple trim strategies,
 * time-based message expiry, and overflow rejection. Token counts are based
 * on actual values reported by Bedrock, not heuristic estimates.
 */
@SdkInternalApi
public final class ContextWindowManager {

    private static final Logger log = Logger.loggerFor(ContextWindowManager.class);

    private final ContextWindowConfig config;
    private final List<Message> messages;
    private final Map<Message, Instant> messageTimestamps;
    private final ConversationSummarizer summarizer;
    private int actualTokenCount;
    private int displayTokenCount;
    private int summarizationCount;

    public ContextWindowManager(ContextWindowConfig config, ConversationSummarizer summarizer) {
        this.config = config;
        this.summarizer = summarizer;
        this.messages = new ArrayList<>();
        this.messageTimestamps = new LinkedHashMap<>();
        this.actualTokenCount = 0;
    }

    public ContextWindowManager(ContextWindowConfig config) {
        this(config, null);
    }

    /**
     * Adds a message to the conversation history. Expired messages are removed first.
     * For REJECT policy, checks against the last known actual token count.
     */
    public void addMessage(Message message) {
        expireOldMessages();

        if (config.overflowPolicy() == ContextWindowConfig.OverflowPolicy.REJECT) {
            int projectedMessages = messages.size() + 1;
            if (actualTokenCount > config.maxTokens() || projectedMessages > config.maxMessages()) {
                throw ContextWindowExceededException.builder()
                    .message("Context window exceeded [actualTokens=" + actualTokenCount
                             + ", maxTokens=" + config.maxTokens()
                             + ", messageCount=" + projectedMessages
                             + ", maxMessages=" + config.maxMessages() + "]")
                    .currentTokens(actualTokenCount)
                    .maxTokens(config.maxTokens())
                    .currentMessages(projectedMessages)
                    .maxMessages(config.maxMessages())
                    .build();
            }
        }

        messages.add(message);
        messageTimestamps.put(message, Instant.now());
    }

    public List<Message> messages() {
        return Collections.unmodifiableList(new ArrayList<>(messages));
    }

    /**
     * Returns the actual token count used for trimming decisions (input + output).
     */
    public int actualTokenCount() {
        return actualTokenCount;
    }

    /**
     * Returns the display token count (input tokens only, what was sent this turn).
     */
    public int displayTokenCount() {
        return displayTokenCount;
    }

    /**
     * Updates token counts and trims if needed.
     * @param inputTokens Actual input tokens (including cache reads) from Bedrock.
     * @param outputTokens Actual output tokens from Bedrock.
     */
    public void updateTokenCountAndTrim(int inputTokens, int outputTokens) {
        this.displayTokenCount = inputTokens;
        this.actualTokenCount = inputTokens + outputTokens;
        trimIfNeeded();
    }

    public void clear() {
        messages.clear();
        messageTimestamps.clear();
        actualTokenCount = 0;
        displayTokenCount = 0;
        summarizationCount = 0;
    }

    public int messageCount() {
        return messages.size();
    }

    public int summarizationCount() {
        return summarizationCount;
    }

    private void expireOldMessages() {
        Duration maxAge = config.maxAge();
        if (maxAge == null) {
            return;
        }
        Instant cutoff = Instant.now().minus(maxAge);
        boolean expired = false;
        while (!messages.isEmpty()) {
            Message oldest = messages.get(0);
            Instant ts = messageTimestamps.get(oldest);
            if (ts != null && ts.isBefore(cutoff)) {
                messages.remove(0);
                messageTimestamps.remove(oldest);
                expired = true;
            } else {
                break;
            }
        }
        if (expired) {
            log.debug(() -> "Expired old messages [remaining=" + messages.size() + "]");
        }
    }

    private void trimIfNeeded() {
        if (!shouldTrim()) {
            return;
        }
        switch (config.contextStrategy()) {
            case SLIDING_WINDOW:
                trimSlidingWindow();
                break;
            case SUMMARIZE:
                trimSummarize();
                break;
            default:
                return;
        }
        log.debug(() -> "Context window trimmed [strategy=" + config.contextStrategy()
                        + ", messageCount=" + messages.size()
                        + ", actualTokens=" + actualTokenCount + "]");
    }

    private boolean shouldTrim() {
        return actualTokenCount > config.maxTokens()
               || messages.size() > config.maxMessages();
    }

    private void trimSlidingWindow() {
        // Trim down to minMessages (Cmin). If Cmin == Cmax, this removes one pair
        // at a time (classic behavior). If Cmin < Cmax, bulk trims to Cmin.
        int target = config.minMessages();
        while (messages.size() > target && messages.size() > 1) {
            removeOldestPair();
        }
    }

    private void trimDropOldestKeepLast() {
        if (messages.size() <= 1) {
            return;
        }
        Message lastMessage = messages.get(messages.size() - 1);
        Instant lastTs = messageTimestamps.get(lastMessage);
        messages.clear();
        messageTimestamps.clear();
        messages.add(lastMessage);
        if (lastTs != null) {
            messageTimestamps.put(lastMessage, lastTs);
        }
        // Token count will be corrected on next API call
        actualTokenCount = 0;
    }

    private void trimSummarize() {
        if (summarizer == null || messages.size() <= 2) {
            trimSlidingWindow();
            return;
        }

        // Keep the last C messages (minMessages), summarize everything before them
        int c = config.minMessages();
        int keepCount = Math.min(c, messages.size() - 1);
        int summarizeEnd = messages.size() - keepCount;

        if (summarizeEnd <= 0) {
            trimSlidingWindow();
            return;
        }

        // Summarize everything older (includes prior summaries)
        List<Message> toSummarize = new ArrayList<>(messages.subList(0, summarizeEnd));
        List<Message> toKeep = new ArrayList<>(messages.subList(summarizeEnd, messages.size()));

        String summaryText = summarizer.summarize(toSummarize);

        Message summaryMsg = Message.builder()
            .role(ConversationRole.ASSISTANT)
            .content(ContentBlock.fromText("[Context Summary] " + summaryText))
            .build();

        messages.clear();
        messageTimestamps.clear();
        messages.add(summaryMsg);
        messageTimestamps.put(summaryMsg, Instant.now());
        for (Message msg : toKeep) {
            messages.add(msg);
        }
        actualTokenCount = 0;
        summarizationCount++;
    }

    private void removeOldestPair() {
        if (messages.size() <= 1) {
            return;
        }
        Message removed = messages.remove(0);
        messageTimestamps.remove(removed);
        if (!messages.isEmpty()) {
            Message second = messages.remove(0);
            messageTimestamps.remove(second);
        }
        // Token count will be corrected on next API call
        actualTokenCount = 0;
    }
}
