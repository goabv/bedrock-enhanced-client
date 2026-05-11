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

import java.time.Duration;
import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;

/**
 * Configuration for enhanced retry behavior specific to Amazon Bedrock.
 *
 * <p>This extends the SDK's default retry behavior with Bedrock-specific handling for:
 * <ul>
 *     <li>{@code ThrottlingException} - with longer backoff to respect rate limits</li>
 *     <li>{@code ModelNotReadyException} - with configurable wait for model warm-up</li>
 *     <li>{@code ModelTimeoutException} - with retry for transient timeouts</li>
 *     <li>{@code ServiceUnavailableException} - with backoff for transient outages</li>
 * </ul>
 */
@SdkPublicApi
public final class BedrockRetryConfig {

    private static final int DEFAULT_MAX_RETRIES = 5;
    private static final Duration DEFAULT_BASE_DELAY = Duration.ofMillis(500);
    private static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(30);
    private static final Duration DEFAULT_THROTTLE_BASE_DELAY = Duration.ofSeconds(2);
    private static final boolean DEFAULT_RETRY_ON_MODEL_NOT_READY = true;

    private final int maxRetries;
    private final Duration baseDelay;
    private final Duration maxBackoff;
    private final Duration throttleBaseDelay;
    private final boolean retryOnModelNotReady;

    private BedrockRetryConfig(Builder builder) {
        this.maxRetries = Validate.isNotNegative(builder.maxRetries, "maxRetries");
        this.baseDelay = Validate.paramNotNull(builder.baseDelay, "baseDelay");
        this.maxBackoff = Validate.paramNotNull(builder.maxBackoff, "maxBackoff");
        this.throttleBaseDelay = Validate.paramNotNull(builder.throttleBaseDelay, "throttleBaseDelay");
        this.retryOnModelNotReady = builder.retryOnModelNotReady;
    }

    /**
     * The maximum number of retry attempts for a failed request.
     */
    public int maxRetries() {
        return maxRetries;
    }

    /**
     * The base delay before the first retry attempt for non-throttling errors.
     */
    public Duration baseDelay() {
        return baseDelay;
    }

    /**
     * The maximum backoff duration between retry attempts.
     */
    public Duration maxBackoff() {
        return maxBackoff;
    }

    /**
     * The base delay before retrying after a throttling error. This is typically
     * longer than {@link #baseDelay()} to respect Bedrock rate limits.
     */
    public Duration throttleBaseDelay() {
        return throttleBaseDelay;
    }

    /**
     * Whether to retry when the model is not yet ready (cold start).
     */
    public boolean retryOnModelNotReady() {
        return retryOnModelNotReady;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return ToString.builder("BedrockRetryConfig")
                       .add("maxRetries", maxRetries)
                       .add("baseDelay", baseDelay)
                       .add("maxBackoff", maxBackoff)
                       .add("throttleBaseDelay", throttleBaseDelay)
                       .add("retryOnModelNotReady", retryOnModelNotReady)
                       .build();
    }

    public static final class Builder {
        private int maxRetries = DEFAULT_MAX_RETRIES;
        private Duration baseDelay = DEFAULT_BASE_DELAY;
        private Duration maxBackoff = DEFAULT_MAX_BACKOFF;
        private Duration throttleBaseDelay = DEFAULT_THROTTLE_BASE_DELAY;
        private boolean retryOnModelNotReady = DEFAULT_RETRY_ON_MODEL_NOT_READY;

        private Builder() {
        }

        public Builder maxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
            return this;
        }

        public Builder baseDelay(Duration baseDelay) {
            this.baseDelay = baseDelay;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        public Builder throttleBaseDelay(Duration throttleBaseDelay) {
            this.throttleBaseDelay = throttleBaseDelay;
            return this;
        }

        public Builder retryOnModelNotReady(boolean retryOnModelNotReady) {
            this.retryOnModelNotReady = retryOnModelNotReady;
            return this;
        }

        public BedrockRetryConfig build() {
            return new BedrockRetryConfig(this);
        }
    }
}
