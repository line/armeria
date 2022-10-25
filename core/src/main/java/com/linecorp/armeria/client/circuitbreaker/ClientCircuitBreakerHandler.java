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
 * A generic handler containing callback methods which are invoked by
 * {@link CircuitBreakerClient}. It may be useful to create a custom
 * implementation in conjunction with {@link CircuitBreakerClientCallbacks}
 * if one wishes to use a custom CircuitBreaker with {@link CircuitBreakerClient}.
 */
@UnstableApi
public interface ClientCircuitBreakerHandler<I extends Request> {

    /**
     * Invoked by {@link CircuitBreakerClient} right before executing a request.
     * A typical implementation may be as follows:
     *
     * <ol>
     *   <li>Extract the appropriate CircuitBreaker implementation.</li>
     *   <li>If the CircuitBreaker is open, throw an appropriate exception.</li>
     *   <li>Otherwise, return the method normally.</li>
     * </ol>
     * If {@code null} is returned, the request will proceed normally as if the {@link CircuitBreakerClient}
     * wasn't added.
     */
    @Nullable
    CircuitBreakerClientCallbacks tryAcquireAndRequest(ClientRequestContext ctx, I req) throws Exception;
}
