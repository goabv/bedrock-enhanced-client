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

import java.util.HashMap;
import java.util.Map;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.enhanced.bedrock.PricingProvider;

/**
 * Built-in static pricing table. No API calls. Prices may be outdated.
 */
@SdkInternalApi
public final class StaticPricingProvider implements PricingProvider {

    // { inputPer1K, outputPer1K, cacheReadPer1K, cacheWritePer1K }
    private static final Map<String, double[]> PRICING = new HashMap<>();

    static {
        put("anthropic.claude-3-haiku-20240307-v1:0", 0.00025, 0.00125, 0.00003, 0.0003);
        put("anthropic.claude-3-sonnet-20240229-v1:0", 0.003, 0.015, 0.0003, 0.00375);
        put("anthropic.claude-3-5-sonnet-20241022-v2:0", 0.003, 0.015, 0.0003, 0.00375);
        put("anthropic.claude-3-5-haiku-20241022-v1:0", 0.0008, 0.004, 0.00008, 0.001);
        put("anthropic.claude-haiku-4-5-20251001-v1:0", 0.0008, 0.004, 0.00008, 0.001);
        put("us.anthropic.claude-haiku-4-5-20251001-v1:0", 0.0008, 0.004, 0.00008, 0.001);
        put("us.anthropic.claude-sonnet-4-20250514-v1:0", 0.003, 0.015, 0.0003, 0.00375);
        put("us.anthropic.claude-sonnet-4-5-20250929-v1:0", 0.003, 0.015, 0.0003, 0.00375);
        put("anthropic.claude-3-opus-20240229-v1:0", 0.015, 0.075, 0.0015, 0.01875);
        put("amazon.nova-micro-v1:0", 0.000035, 0.00014, 0.0, 0.0);
        put("amazon.nova-lite-v1:0", 0.00006, 0.00024, 0.0, 0.0);
        put("us.amazon.nova-lite-v1:0", 0.00006, 0.00024, 0.0, 0.0);
        put("amazon.nova-pro-v1:0", 0.0008, 0.0032, 0.0, 0.0);
        put("us.amazon.nova-pro-v1:0", 0.0008, 0.0032, 0.0, 0.0);
    }

    private static void put(String id, double in, double out, double cr, double cw) {
        PRICING.put(id, new double[]{in, out, cr, cw});
    }

    @Override
    public double inputPricePer1K(String modelId) {
        double[] p = PRICING.get(modelId);
        return p != null ? p[0] : 0.0;
    }

    @Override
    public double outputPricePer1K(String modelId) {
        double[] p = PRICING.get(modelId);
        return p != null ? p[1] : 0.0;
    }

    @Override
    public double cacheReadPricePer1K(String modelId) {
        double[] p = PRICING.get(modelId);
        return p != null ? p[2] : 0.0;
    }

    @Override
    public double cacheWritePricePer1K(String modelId) {
        double[] p = PRICING.get(modelId);
        return p != null ? p[3] : 0.0;
    }
}
