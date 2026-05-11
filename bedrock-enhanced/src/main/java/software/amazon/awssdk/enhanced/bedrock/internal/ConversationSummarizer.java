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

import java.util.List;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.services.bedrockruntime.model.Message;

/**
 * Callback interface for summarizing a list of conversation messages into a
 * single summary string. Used by the SUMMARIZE trim strategy.
 */
@SdkInternalApi
@FunctionalInterface
public interface ConversationSummarizer {

    /**
     * Summarizes the given messages into a concise text representation.
     *
     * @param messages The messages to summarize.
     * @return A summary string capturing the key information from the messages.
     */
    String summarize(List<Message> messages);
}
