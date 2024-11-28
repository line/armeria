/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.healthcheck;

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Handles {@code PUT}, {@code POST} or {@code PATCH} requests sent to {@link HealthCheckService}.
 */
@FunctionalInterface
public interface HealthCheckUpdateHandler {

    /**
     * Returns the default {@link HealthCheckUpdateHandler} that accepts a JSON object which has a boolean
     * property named {@code "healthy"} for a {@code PUT} or {@code POST} request. A JSON patch in a
     * {@code PATCH} request is also accepted.
     *
     * <p>For example:
     * <pre>{@code
     * // Update healthiness of the server to unhealthy
     * POST /internal/health HTTP/2.0
     *
     * { "healthy": false }
     *
     * // Patch healthiness of the server to unhealthy
     * PATCH /internal/health HTTP/2.0
     *
     * [ { "op": "replace", "path": "/healthy", "value": false } ]
     * }</pre>
     */
    @UnstableApi
    static HealthCheckUpdateHandler of() {
        return DefaultHealthCheckUpdateHandler.INSTANCE;
    }

    /**
     * Determines if the healthiness of the {@link Server} needs to be changed or not from the given
     * {@link HttpRequest}.
     *
     * @return A {@link CompletionStage} which is completed with {@link HealthCheckUpdateResult}.
     *         The {@link CompletionStage} can also be completed with an exception, such as
     *         {@link HttpStatusException} and {@link HttpResponseException} to send a specific
     *         HTTP response to the client.
     */
    CompletionStage<HealthCheckUpdateResult> handle(ServiceRequestContext ctx,
                                                    HttpRequest req) throws Exception;
}
