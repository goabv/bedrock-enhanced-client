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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pricing.PricingClient;
import software.amazon.awssdk.services.pricing.model.Filter;
import software.amazon.awssdk.services.pricing.model.FilterType;
import software.amazon.awssdk.services.pricing.model.GetProductsRequest;
import software.amazon.awssdk.services.pricing.model.GetProductsResponse;
import software.amazon.awssdk.utils.Logger;

/**
 * Fetches Bedrock model pricing from the AWS Pricing API. Results are cached
 * in memory per model ID. Falls back to zero pricing on errors.
 *
 * <p>The Pricing API is only available in us-east-1 and ap-south-1.
 */
@SdkInternalApi
public final class ApiPricingProvider implements PricingProvider {

    private static final Logger log = Logger.loggerFor(ApiPricingProvider.class);
    private static final String SERVICE_CODE = "AmazonBedrock";

    private final ConcurrentMap<String, double[]> cache = new ConcurrentHashMap<>();
    private final PricingClient pricingClient;
    private final boolean ownClient;

    public ApiPricingProvider() {
        this.pricingClient = PricingClient.builder()
            .region(Region.US_EAST_1)
            .build();
        this.ownClient = true;
    }

    public ApiPricingProvider(PricingClient pricingClient) {
        this.pricingClient = pricingClient;
        this.ownClient = false;
    }

    @Override
    public double inputPricePer1K(String modelId) {
        return getPricing(modelId)[0];
    }

    @Override
    public double outputPricePer1K(String modelId) {
        return getPricing(modelId)[1];
    }

    @Override
    public double cacheReadPricePer1K(String modelId) {
        return getPricing(modelId)[2];
    }

    @Override
    public double cacheWritePricePer1K(String modelId) {
        return getPricing(modelId)[3];
    }

    private double[] getPricing(String modelId) {
        return cache.computeIfAbsent(modelId, this::fetchPricing);
    }

    private double[] fetchPricing(String modelId) {
        try {
            // Query for input token pricing
            double inputPrice = fetchPrice(modelId, "Input");
            double outputPrice = fetchPrice(modelId, "Output");
            // Cache pricing is typically 10% of input for reads, 125% for writes
            // The Pricing API may not have separate cache SKUs, so estimate
            double cacheReadPrice = inputPrice * 0.1;
            double cacheWritePrice = inputPrice * 1.25;

            log.debug(() -> "Fetched pricing for " + modelId
                            + " [input=" + inputPrice + ", output=" + outputPrice + "]");

            return new double[]{inputPrice, outputPrice, cacheReadPrice, cacheWritePrice};
        } catch (Exception e) {
            log.warn(() -> "Failed to fetch pricing for " + modelId
                           + ", falling back to zero pricing: " + e.getMessage());
            return new double[]{0.0, 0.0, 0.0, 0.0};
        }
    }

    private double fetchPrice(String modelId, String usageType) {
        GetProductsRequest request = GetProductsRequest.builder()
            .serviceCode(SERVICE_CODE)
            .filters(
                Filter.builder()
                    .type(FilterType.TERM_MATCH)
                    .field("modelId")
                    .value(modelId)
                    .build(),
                Filter.builder()
                    .type(FilterType.TERM_MATCH)
                    .field("usagetype")
                    .value(usageType)
                    .build()
            )
            .formatVersion("aws_v1")
            .maxResults(1)
            .build();

        GetProductsResponse response = pricingClient.getProducts(request);
        List<String> priceList = response.priceList();

        if (priceList == null || priceList.isEmpty()) {
            return 0.0;
        }

        // Parse the price from the JSON response
        // The price list JSON contains nested pricing terms
        String json = priceList.get(0);
        return extractPriceFromJson(json);
    }

    /**
     * Extracts the per-unit price from the Pricing API JSON response.
     * The JSON structure is deeply nested; we look for "pricePerUnit" -> "USD".
     */
    private double extractPriceFromJson(String json) {
        // Simple extraction: find "USD":"<value>" pattern
        String marker = "\"USD\":\"";
        int idx = json.indexOf(marker);
        if (idx < 0) {
            return 0.0;
        }
        int start = idx + marker.length();
        int end = json.indexOf("\"", start);
        if (end < 0) {
            return 0.0;
        }
        try {
            return Double.parseDouble(json.substring(start, end));
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
