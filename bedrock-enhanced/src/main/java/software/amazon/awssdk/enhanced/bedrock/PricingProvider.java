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

/**
 * Provides per-token pricing for Bedrock models. Two built-in implementations:
 * <ul>
 *     <li>{@link #api()} — fetches live pricing from the AWS Pricing API (default)</li>
 *     <li>{@link #static_()} — uses a built-in pricing table (no API calls)</li>
 * </ul>
 *
 * <p>Users can also implement this interface to provide custom pricing.
 */
@SdkPublicApi
public interface PricingProvider {

    /** Price per 1000 input tokens in USD. */
    double inputPricePer1K(String modelId);

    /** Price per 1000 output tokens in USD. */
    double outputPricePer1K(String modelId);

    /** Price per 1000 cache-read tokens in USD. */
    double cacheReadPricePer1K(String modelId);

    /** Price per 1000 cache-write tokens in USD. */
    double cacheWritePricePer1K(String modelId);

    /**
     * Creates a pricing provider that fetches live pricing from the AWS Pricing API.
     * Prices are cached in memory after the first lookup per model.
     * Requires {@code pricing} service permissions.
     */
    static PricingProvider api() {
        return new software.amazon.awssdk.enhanced.bedrock.internal.ApiPricingProvider();
    }

    /**
     * Creates a pricing provider using a built-in static pricing table.
     * No API calls are made. Prices may be outdated.
     */
    static PricingProvider builtIn() {
        return new software.amazon.awssdk.enhanced.bedrock.internal.StaticPricingProvider();
    }
}
