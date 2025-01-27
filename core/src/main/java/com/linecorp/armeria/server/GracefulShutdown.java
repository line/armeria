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

package com.linecorp.armeria.server;

import java.time.Duration;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ShuttingDownException;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Configures the graceful shutdown behavior of a {@link Server}.
 */
@UnstableApi
public interface GracefulShutdown {

    /**
     * Returns a new {@link GracefulShutdownBuilder}.
     */
    static GracefulShutdownBuilder builder() {
        return new GracefulShutdownBuilder();
    }

    /**
     * Returns a {@link GracefulShutdown} that disables the graceful shutdown feature.
     */
    static GracefulShutdown disabled() {
        return GracefulShutdownBuilder.DISABLED;
    }

    /**
     * Returns the quiet period to wait for active requests to go end before shutting down.
     * {@link Duration#ZERO} means the server will stop right away without waiting.
     */
    Duration quietPeriod();

    /**
     * Returns the amount of time to wait before shutting down the server regardless of active requests.
     * This should be set to a time greater than {@code quietPeriod} to ensure the server shuts down even
     * if there is a stuck request.
     */
    Duration timeout();

    /**
     * Returns an {@link Throwable} to terminate a pending request when the server is shutting down.
     * The exception will be converted to an {@link HttpResponse} by {@link ServerErrorHandler}.
     *
     * <p>If null is returned, the request will be terminated with {@link ShuttingDownException} that will be
     * converted to an {@link HttpStatus#SERVICE_UNAVAILABLE} response.
     */
    Throwable toException(ServiceRequestContext ctx, HttpRequest request);
}
