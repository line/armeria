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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides the properties and operations required for sending health check requests.
 */
public interface HealthCheckerContext {

    /**
     * Returns the {@link Endpoint} to send health check requests to.
     */
    Endpoint endpoint();

    /**
     * Returns the {@link SessionProtocol} to be used when sending health check requests.
     */
    SessionProtocol protocol();

    /**
     * Returns the {@link ClientOptions} of the {@link Client} that sends health check requests.
     */
    ClientOptions clientOptions();

    /**
     * Returns the {@link ScheduledExecutorService} which is used for scheduling the tasks related with
     * sending health check requests. Note that the {@link ScheduledExecutorService} returned by this method
     * cannot be shut down; calling {@link ExecutorService#shutdown()} or {@link ExecutorService#shutdownNow()}
     * will trigger an {@link UnsupportedOperationException}.
     */
    ScheduledExecutorService executor();

    /**
     * Returns the delay for the next health check request in milliseconds.
     */
    long nextDelayMillis();

    /**
     * Updates the health of the {@link Endpoint} being checked.
     *
     * @param health {@code 0.0} indicates the {@link Endpoint} is not able to handle any requests.
     *               A positive value indicates the {@link Endpoint} is able to handle requests.
     *               A value greater than {@code 1.0} will be set equal to {@code 1.0}.
     *
     * @deprecated Use {@link #updateHealth(double, ClientRequestContext, ResponseHeaders, Throwable)}.
     */
    @Deprecated
    void updateHealth(double health);

    /**
     * Updates the health of the {@link Endpoint} being checked.
     *
     * @param health {@code 0.0} indicates the {@link Endpoint} is not able to handle any requests.
     *               A positive value indicates the {@link Endpoint} is able to handle requests.
     *               A value greater than {@code 1.0} will be set equal to {@code 1.0}.
     * @param ctx the {@link ClientRequestContext} of the health check request.
     * @param headers the {@link ResponseHeaders} of the health check request.
     *                {@code null} if the request is failed with the {@code cause}.
     * @param cause the cause of the failed health check request.
     *              {@code null} if the health checked request received the {@code headers}.
     */
    default void updateHealth(double health, ClientRequestContext ctx,
                              @Nullable ResponseHeaders headers, @Nullable Throwable cause) {
        // TODO(ikhoon): Make this method abstract in Armeria 2.0
        updateHealth(health);
    }

    /**
     * Provides the parameters for health checking.
     */
    Supplier<HealthCheckerParams> paramsFactory();
}
