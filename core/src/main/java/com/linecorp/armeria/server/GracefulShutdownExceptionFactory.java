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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A factory that creates a {@link Throwable} to terminate a pending request when the server is
 * shutting down.
 */
@UnstableApi
@FunctionalInterface
public interface GracefulShutdownExceptionFactory {

    /**
     * Creates a {@link Throwable} that can be used to terminate a pending request
     * in a specific {@link ServiceRequestContext}, typically during server shutdown.
     *
     * @param serviceRequestContext the context of the service request which may be affected by the shutdown
     * @param httpRequest the HTTP request associated with the service request context
     * @return the {@link Throwable} to indicate the termination of the request, or {@code null} if no
     *         throwable should be thrown in this context
     */
    @Nullable
    Throwable createThrowableForContext(ServiceRequestContext serviceRequestContext, HttpRequest httpRequest);

}
