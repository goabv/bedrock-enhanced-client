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

import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.utils.Logger;

/**
 * A simple token-bucket rate limiter for client-side throttling. Supports adaptive
 * rate reduction when server-side throttling is detected.
 */
@SdkInternalApi
public final class TokenBucketRateLimiter {

    private static final Logger log = Logger.loggerFor(TokenBucketRateLimiter.class);
    private static final double THROTTLE_REDUCTION_FACTOR = 0.5;
    private static final double RECOVERY_INCREASE_FACTOR = 0.1;

    private final double maxRate;
    private final boolean adaptiveEnabled;
    private final Object lock = new Object();

    private double currentRate;
    private double availableTokens;
    private long lastRefillTimestamp;

    public TokenBucketRateLimiter(double maxRequestsPerSecond, boolean adaptiveEnabled) {
        this.maxRate = maxRequestsPerSecond;
        this.currentRate = maxRequestsPerSecond;
        this.adaptiveEnabled = adaptiveEnabled;
        this.availableTokens = maxRequestsPerSecond;
        this.lastRefillTimestamp = System.nanoTime();
    }

    /**
     * Acquires a permit, blocking until one is available. Returns the time waited in milliseconds.
     */
    public long acquire() {
        long waitTimeMs;
        synchronized (lock) {
            refill();
            if (availableTokens < 1.0) {
                double deficit = 1.0 - availableTokens;
                long computedWait = (long) (deficit / currentRate * 1000);
                if (computedWait > 0) {
                    long deadline = System.nanoTime() + computedWait * 1_000_000;
                    while (availableTokens < 1.0) {
                        long remainingMs = (deadline - System.nanoTime()) / 1_000_000;
                        if (remainingMs <= 0) {
                            break;
                        }
                        try {
                            lock.wait(remainingMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for rate limiter permit", e);
                        }
                        refill();
                    }
                }
                waitTimeMs = computedWait;
            } else {
                waitTimeMs = 0;
            }
            availableTokens -= 1.0;
        }
        if (waitTimeMs > 0) {
            long logWaitMs = waitTimeMs;
            log.debug(() -> "Rate limiter waited " + logWaitMs + "ms before allowing request "
                            + "[currentRate=" + currentRate + "/s]");
        }
        return waitTimeMs;
    }

    /**
     * Notifies the rate limiter that a request was throttled by the server.
     * If adaptive throttling is enabled, the rate is reduced.
     */
    public void notifyThrottled() {
        if (!adaptiveEnabled) {
            return;
        }
        synchronized (lock) {
            double previousRate = currentRate;
            currentRate = Math.max(currentRate * THROTTLE_REDUCTION_FACTOR, 0.1);
            log.debug(() -> "Adaptive throttling reduced rate from " + previousRate
                            + " to " + currentRate + " requests/s");
        }
    }

    /**
     * Notifies the rate limiter that a request succeeded. If adaptive throttling is
     * enabled, the rate is gradually increased back toward the maximum.
     */
    public void notifySuccess() {
        if (!adaptiveEnabled) {
            return;
        }
        synchronized (lock) {
            if (currentRate < maxRate) {
                currentRate = Math.min(currentRate + (maxRate * RECOVERY_INCREASE_FACTOR), maxRate);
            }
        }
    }

    /**
     * Returns the current effective rate in requests per second.
     */
    public double currentRate() {
        synchronized (lock) {
            return currentRate;
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsed = (now - lastRefillTimestamp) / 1_000_000_000.0;
        availableTokens = Math.min(availableTokens + elapsed * currentRate, currentRate);
        lastRefillTimestamp = now;
    }
}
