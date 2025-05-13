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

package com.linecorp.armeria.internal.client;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.RequestContextExtension;

/**
 * This class exposes extension methods for {@link ClientRequestContext}
 * which are used internally by Armeria but aren't intended for public usage.
 */
public interface ClientRequestContextExtension extends ClientRequestContext, RequestContextExtension {

    /**
     * Returns the {@link CancellationScheduler} used to schedule a response timeout.
     */
    CancellationScheduler responseCancellationScheduler();

    /**
     * Returns a {@link CompletableFuture} that will be completed
     * if this {@link ClientRequestContext} is initialized with an {@link EndpointGroup}.
     *
     * @see #init()
     */
    CompletableFuture<Boolean> whenInitialized();

    /**
     * Initializes this context with the specified {@link EndpointGroup}.
     * This method must be invoked to finish the construction of this context.
     *
     * @return {@code true} if the initialization has succeeded.
     *         {@code false} if the initialization has failed and this context's {@link RequestLog} has been
     *         completed with the cause of the failure.
     */
    CompletableFuture<Boolean> init();

    /**
     * Completes the {@link #whenInitialized()} with the specified value.
     */
    void finishInitialization(boolean success);

    /**
     * A set of internal headers which are set by armeria internally.
     * These headers are merged with the lowest priority before getting sent over the wire.
     * <ol>
     *     <li>additional headers</li>
     *     <li>request headers</li>
     *     <li>default headers</li>
     *     <li>internal headers</li>
     * </ol>
     * Most notably, {@link HttpHeaderNames#AUTHORITY} and {@link HttpHeaderNames#USER_AGENT} are set
     * with default values on every request.
     */
    HttpHeaders internalRequestHeaders();

    long remainingTimeoutNanos();

    /**
     * The context customizer must be run before the following conditions.
     * <li>
     *     <ul>
     *         {@link EndpointSelector#selectNow(ClientRequestContext)} so that the customizer
     *         can inject the attributes which may be required by the EndpointSelector.</ul>
     *     <ul>
     *         mapEndpoint() to give an opportunity to override an Endpoint when using
     *         an additional authority.
     *     </ul>
     * </li>
     */
    void runContextCustomizer();

    ClientDecoration decoration();

    void decoration(ClientDecoration decoration);
}
