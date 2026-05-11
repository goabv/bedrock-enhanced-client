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

package software.amazon.awssdk.enhanced.bedrock;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.utils.Validate;

/**
 * Request to create a new {@link ChatSession} with optional configuration overrides.
 */
@SdkPublicApi
public final class CreateSessionRequest {

    private final String modelId;
    private final List<SystemContentBlock> systemPrompts;
    private final InferenceConfiguration inferenceConfig;
    private final ContextWindowConfig contextWindowConfig;
    private final Integer tokenBudget;

    private CreateSessionRequest(Builder builder) {
        this.modelId = Validate.paramNotBlank(builder.modelId, "modelId");
        this.systemPrompts = Collections.unmodifiableList(new ArrayList<>(builder.systemPrompts));
        this.inferenceConfig = builder.inferenceConfig;
        this.contextWindowConfig = builder.contextWindowConfig;
        this.tokenBudget = builder.tokenBudget;
    }

    public String modelId() {
        return modelId;
    }

    /**
     * System prompts to include at the beginning of every conversation turn.
     */
    public List<SystemContentBlock> systemPrompts() {
        return systemPrompts;
    }

    /**
     * Inference configuration (temperature, maxTokens, topP, stopSequences) for this session.
     */
    public InferenceConfiguration inferenceConfig() {
        return inferenceConfig;
    }

    /**
     * Session-level context window config override. If null, the client-level config is used.
     */
    public ContextWindowConfig contextWindowConfig() {
        return contextWindowConfig;
    }

    /**
     * Optional total token budget for this session. When exceeded, further calls will throw
     * {@link TokenBudgetExceededException}.
     */
    public Integer tokenBudget() {
        return tokenBudget;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String modelId;
        private List<SystemContentBlock> systemPrompts = new ArrayList<>();
        private InferenceConfiguration inferenceConfig;
        private ContextWindowConfig contextWindowConfig;
        private Integer tokenBudget;

        private Builder() {
        }

        public Builder modelId(String modelId) {
            this.modelId = modelId;
            return this;
        }

        public Builder systemPrompts(List<SystemContentBlock> systemPrompts) {
            this.systemPrompts = systemPrompts != null ? new ArrayList<>(systemPrompts) : new ArrayList<>();
            return this;
        }

        public Builder addSystemPrompt(SystemContentBlock systemPrompt) {
            this.systemPrompts.add(systemPrompt);
            return this;
        }

        public Builder inferenceConfig(InferenceConfiguration inferenceConfig) {
            this.inferenceConfig = inferenceConfig;
            return this;
        }

        public Builder contextWindowConfig(ContextWindowConfig contextWindowConfig) {
            this.contextWindowConfig = contextWindowConfig;
            return this;
        }

        public Builder tokenBudget(Integer tokenBudget) {
            this.tokenBudget = tokenBudget;
            return this;
        }

        public CreateSessionRequest build() {
            return new CreateSessionRequest(this);
        }
    }
}
