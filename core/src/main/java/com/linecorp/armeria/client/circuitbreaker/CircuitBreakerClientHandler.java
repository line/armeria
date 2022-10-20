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
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A generic handler containing callback methods which are invoked by
 * {@link CircuitBreakerClient}. It may be useful to create a custom
 * implementation in conjunction with {@link CircuitBreakerClientHandlerFactory}
 * if one wishes to use a custom CircuitBreaker with {@link CircuitBreakerClient}.
 */
@UnstableApi
public interface CircuitBreakerClientHandler<I extends Request> {

    /**
     * Invoked by {@link CircuitBreakerClient} right before executing a request.
     * A typical implementation may be as follows:
     *
     * <ol>
     *   <li>Extract the appropriate CircuitBreaker implementation.</li>
     *   <li>If the CircuitBreaker is open, throw an appropriate exception.</li>
     *   <li>Otherwise, return the method normally.</li>
     * </ol>
     * A {@link CircuitBreakerAbortException} may be thrown if a user wishes to abort
     * reporting and proceed with the request normally as if the {@link CircuitBreakerClient}
     * wasn't added.
     */
    void tryAcquireAndRequest(ClientRequestContext ctx, I req);

    /**
     * Invoked by {@link CircuitBreakerClient} after a request has been executed.
     * The resulting {@link CircuitBreakerDecision} may be used to determine whether
     * the result has been successful.
     * A typical implementation would
     * <ol>
     *   <li>Extract the appropriate CircuitBreaker implementation.</li>
     *   <li>Update the CircuitBreaker state according to the provided {@link CircuitBreakerDecision}</li>
     * </ol>
     *
     * @param throwable a hint to determine why a request has failed. A CircuitBreaker may use this value to
     *                  make more informed decisions on how to record a failure event. Note that there are no
     *                  guarantees on the nullability of this value. (e.g. this value can be {@code null}
     *                  even if a request has failed)
     */
    void reportSuccessOrFailure(ClientRequestContext ctx,
                                CompletionStage<CircuitBreakerDecision> future,
                                @Nullable Throwable throwable);
}
