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

import software.amazon.awssdk.annotations.SdkPublicApi;
import software.amazon.awssdk.utils.ToString;
import software.amazon.awssdk.utils.Validate;

/**
 * Configuration for client-side throttling. Uses a token-bucket rate limiter to
 * proactively limit request rates and avoid server-side throttling from Bedrock.
 */
@SdkPublicApi
public final class ThrottlingConfig {

    private static final double DEFAULT_MAX_REQUESTS_PER_SECOND = 10.0;
    private static final boolean DEFAULT_ADAPTIVE_ENABLED = true;

    private final double maxRequestsPerSecond;
    private final boolean adaptiveEnabled;

    private ThrottlingConfig(Builder builder) {
        this.maxRequestsPerSecond = builder.maxRequestsPerSecond;
        Validate.isPositive(this.maxRequestsPerSecond, "maxRequestsPerSecond");
        this.adaptiveEnabled = builder.adaptiveEnabled;
    }

    /**
     * The maximum number of requests per second allowed by the client-side rate limiter.
     */
    public double maxRequestsPerSecond() {
        return maxRequestsPerSecond;
    }

    /**
     * Whether adaptive throttling is enabled. When enabled, the rate limiter automatically
     * reduces throughput when server-side throttling is detected and gradually increases
     * it when requests succeed.
     */
    public boolean adaptiveEnabled() {
        return adaptiveEnabled;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return ToString.builder("ThrottlingConfig")
                       .add("maxRequestsPerSecond", maxRequestsPerSecond)
                       .add("adaptiveEnabled", adaptiveEnabled)
                       .build();
    }

    public static final class Builder {
        private double maxRequestsPerSecond = DEFAULT_MAX_REQUESTS_PER_SECOND;
        private boolean adaptiveEnabled = DEFAULT_ADAPTIVE_ENABLED;

        private Builder() {
        }

        public Builder maxRequestsPerSecond(double maxRequestsPerSecond) {
            this.maxRequestsPerSecond = maxRequestsPerSecond;
            return this;
        }

        public Builder adaptiveEnabled(boolean adaptiveEnabled) {
            this.adaptiveEnabled = adaptiveEnabled;
            return this;
        }

        public ThrottlingConfig build() {
            return new ThrottlingConfig(this);
        }
    }
}
