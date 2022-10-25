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

package com.linecorp.armeria.client.circuitbreaker;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.CompletionActions;

final class CircuitBreakerReporterUtil {

    static void reportSuccessOrFailure(CircuitBreakerClientCallbacks callbacks,
                                       ClientRequestContext ctx,
                                       CompletionStage<@Nullable CircuitBreakerDecision> future,
                                       @Nullable Throwable throwable) {
        future.handle((decision, unused) -> {
            if (decision != null) {
                if (decision == CircuitBreakerDecision.success() ||
                    decision == CircuitBreakerDecision.next()) {
                    callbacks.onSuccess(ctx);
                } else if (decision == CircuitBreakerDecision.failure()) {
                    callbacks.onFailure(ctx, throwable);
                } else {
                    // Ignore, does not count as a success nor failure.
                }
            }
            return null;
        }).exceptionally(CompletionActions::log);
    }

    private CircuitBreakerReporterUtil() {}
}
