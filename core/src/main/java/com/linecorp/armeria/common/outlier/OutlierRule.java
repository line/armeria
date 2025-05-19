/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.outlier;

import static com.linecorp.armeria.common.outlier.OutlierDetectingRuleBuilder.DEFAULT_RULE;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A rule to detect outliers.
 *
 * <p>Outliers are detected based on the response headers and the cause of the failure.
 * Example:
 * <pre>{@code
 * OutlierDetectingRule rule =
 *   OutlierDetectingRule
 *     .builder()
 *     // Detects 5xx as a failure.
 *     .onServerError()
 *     // Detects any exception as a failure.
 *     .onException()
 *     .build();
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface OutlierRule {

    /**
     * Returns the default {@link OutlierRule} that detects 5xx statuses and any exception as a
     * failure.
     */
    static OutlierRule of() {
        return DEFAULT_RULE;
    }

    /**
     * Returns a new {@link OutlierDetectingRuleBuilder}.
     */
    static OutlierDetectingRuleBuilder builder() {
        return new OutlierDetectingRuleBuilder();
    }

    /**
     * Decides whether the request is an outlier.
     */
    OutlierDetectionDecision decide(RequestContext ctx,
                                    @Nullable ResponseHeaders headers,
                                    @Nullable Throwable cause);

    /**
     * Returns a composed {@link OutlierRule} that represents a logical OR of
     * this {@link OutlierRule} and another. If this {@link OutlierRule} returns
     * {@link OutlierDetectionDecision#NEXT}, then other {@link OutlierDetectionDecision} is evaluated.
     */
    default OutlierRule orElse(OutlierRule other) {
        requireNonNull(other, "other");
        return (ctx, headers, cause) -> {
            final OutlierDetectionDecision decision = decide(ctx, headers, cause);
            if (decision == null || decision == OutlierDetectionDecision.NEXT) {
                return other.decide(ctx, headers, cause);
            }
            return decision;
        };
    }
}
