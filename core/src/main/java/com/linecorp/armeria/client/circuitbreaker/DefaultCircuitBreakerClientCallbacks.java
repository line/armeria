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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

final class DefaultCircuitBreakerClientCallbacks implements CircuitBreakerClientCallbacks {

    private final CircuitBreaker circuitBreaker;

    DefaultCircuitBreakerClientCallbacks(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = requireNonNull(circuitBreaker, "circuitBreaker");
    }

    @Override
    public void onSuccess(ClientRequestContext ctx) {
        circuitBreaker.onSuccess();
    }

    @Override
    public void onFailure(ClientRequestContext ctx, @Nullable Throwable throwable) {
        circuitBreaker.onFailure();
    }
}
