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
import java.util.Random;
import java.util.concurrent.Callable;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.BedrockRetryConfig;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.utils.Logger;

/**
 * Bedrock-specific retry handler that applies enhanced retry logic with different
 * backoff strategies for throttling vs. transient errors.
 */
@SdkInternalApi
public final class BedrockRetryHandler {

    private static final Logger log = Logger.loggerFor(BedrockRetryHandler.class);

    private final BedrockRetryConfig config;
    private final TokenBucketRateLimiter rateLimiter;
    private final Random random;

    public BedrockRetryHandler(BedrockRetryConfig config, TokenBucketRateLimiter rateLimiter) {
        this.config = config;
        this.rateLimiter = rateLimiter;
        this.random = new Random();
    }

    /**
     * Executes the given callable with Bedrock-specific retry logic.
     *
     * @param <T>      The return type.
     * @param callable The operation to execute.
     * @return The result of the callable.
     */
    public <T> T executeWithRetry(Callable<T> callable) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= config.maxRetries(); attempt++) {
            try {
                if (attempt > 0) {
                    int currentAttempt = attempt;
                    log.debug(() -> "Retry attempt [attemptNumber=" + currentAttempt
                                    + ", maxRetries=" + config.maxRetries() + "]");
                }
                T result = callable.call();
                if (rateLimiter != null) {
                    rateLimiter.notifySuccess();
                }
                return result;
            } catch (ThrottlingException e) {
                lastException = e;
                if (rateLimiter != null) {
                    rateLimiter.notifyThrottled();
                }
                if (attempt < config.maxRetries()) {
                    sleepWithJitter(config.throttleBaseDelay(), attempt);
                }
            } catch (ModelNotReadyException e) {
                lastException = e;
                if (!config.retryOnModelNotReady() || attempt >= config.maxRetries()) {
                    break;
                }
                sleepWithJitter(config.baseDelay(), attempt);
            } catch (ModelTimeoutException e) {
                lastException = e;
                if (attempt >= config.maxRetries()) {
                    break;
                }
                sleepWithJitter(config.baseDelay(), attempt);
            } catch (ServiceUnavailableException e) {
                lastException = e;
                if (attempt >= config.maxRetries()) {
                    break;
                }
                sleepWithJitter(config.baseDelay(), attempt);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException("Unexpected checked exception during Bedrock call", e);
            }
        }

        if (lastException instanceof RuntimeException) {
            throw (RuntimeException) lastException;
        }
        throw new RuntimeException("Bedrock call failed after " + config.maxRetries() + " retries", lastException);
    }

    private void sleepWithJitter(Duration baseDelay, int attempt) {
        long baseMs = baseDelay.toMillis();
        long exponentialMs = baseMs * (1L << Math.min(attempt, 20));
        long cappedMs = Math.min(exponentialMs, config.maxBackoff().toMillis());
        // Full jitter: sleep for random duration between 0 and cappedMs
        long jitteredMs = cappedMs > 0 ? (long) (random.nextDouble() * cappedMs) : 0;
        long sleepMs = Math.max(jitteredMs, 1);

        log.debug(() -> "Sleeping before retry [delay=" + sleepMs + "ms, attempt=" + attempt + "]");

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted during retry backoff", e);
        }
    }
}
