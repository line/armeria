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

package com.linecorp.armeria.common.circuitbreaker;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientHandler;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A callback that is invoked for each request by {@link CircuitBreakerClient}.
 * Users may implement this class in conjunction with {@link CircuitBreakerClientHandler} to
 * use arbitrary circuit breaker implementations with {@link CircuitBreakerClient}.
 *
 * @see CircuitBreakerClientHandler
 */
@UnstableApi
public interface CircuitBreakerCallback {

    /**
     * Invoked by {@link CircuitBreakerClient} if a request has succeeded.
     */
    void onSuccess(RequestContext ctx);

    /**
     * Invoked by {@link CircuitBreakerClient} if a request has failed.
     *
     * @param throwable a hint for why a request has failed. A {@link CircuitBreaker} may use this value to
     *                  make more informed decisions on how to record a failure event.
     */
    void onFailure(RequestContext ctx, @Nullable Throwable throwable);
}
