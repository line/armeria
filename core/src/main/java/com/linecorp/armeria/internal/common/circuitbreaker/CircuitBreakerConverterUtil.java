/*
 * Copyright 2022 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.internal.common.circuitbreaker;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerDecision;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

public final class CircuitBreakerConverterUtil {

    public static <T extends Response> CircuitBreakerRuleWithContent<T> fromCircuitBreakerRule(
            CircuitBreakerRule circuitBreakerRule) {
        return new CircuitBreakerRuleWithContent<T>() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable T response,
                                                                                 @Nullable Throwable cause) {
                return circuitBreakerRule.shouldReportAsSuccess(ctx, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return circuitBreakerRule.requiresResponseTrailers();
            }
        };
    }

    public static <T extends Response> CircuitBreakerRule fromCircuitBreakerRuleWithContent(
            CircuitBreakerRuleWithContent<T> circuitBreakerRuleWithContent) {
        return new CircuitBreakerRule() {
            @Override
            public CompletionStage<CircuitBreakerDecision> shouldReportAsSuccess(ClientRequestContext ctx,
                                                                                 @Nullable Throwable cause) {
                return circuitBreakerRuleWithContent.shouldReportAsSuccess(ctx, null, cause);
            }

            @Override
            public boolean requiresResponseTrailers() {
                return circuitBreakerRuleWithContent.requiresResponseTrailers();
            }
        };
    }

    private CircuitBreakerConverterUtil() {}
}
