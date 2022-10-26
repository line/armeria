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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A collection of callbacks that are invoked for each request by {@link CircuitBreakerClient}.
 * Users may implement this class in conjunction with {@link CircuitBreakerClientHandler} to
 * use arbitrary CircuitBreaker implementations with {@link CircuitBreakerClient}.
 * See {@link CircuitBreakerClientHandler#request(ClientRequestContext, Request)}
 * for more information.
 */
@UnstableApi
public interface CircuitBreakerClientCallbacks {

    /**
     * Invoked by {@link CircuitBreakerClient} if a request has succeeded.
     */
    void onSuccess(ClientRequestContext ctx);

    /**
     * Invoked by {@link CircuitBreakerClient} if a request has failed.
     *
     * @param throwable a hint for why a request has failed. A CircuitBreaker may use this value to
     *                  make more informed decisions on how to record a failure event. Note that there are no
     *                  guarantees on the nullability of this value.
     */
    void onFailure(ClientRequestContext ctx, @Nullable Throwable throwable);
}
