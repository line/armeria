/*
 * Copyright 2016 LINE Corporation
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

import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * Provides the properties and operations required for sending health check requests.
 */
public interface HealthCheckerContext {

    /**
     * Returns the {@link Endpoint} to sent health check requests to.
     */
    Endpoint endpoint();

    /**
     * Returns the {@link ClientFactory} which is used for sending health check requests.
     */
    ClientFactory clientFactory();

    /**
     * Returns the {@link SessionProtocol} to be used when sending health check requests.
     */
    SessionProtocol protocol();

    /**
     * Returns the port where a health check request will be sent instead of the original port number
     * specified by {@link Endpoint}s.
     *
     * @return {@code 0} to send to the original port, or the alternative port number.
     */
    int port();

    /**
     * Returns the {@link Function} that customizes a {@link Client} that sends health check requests.
     */
    Function<? super ClientOptionsBuilder, ClientOptionsBuilder> clientConfigurator();

    /**
     * Returns the {@link ScheduledExecutorService} which is used for scheduling the tasks related with
     * sending health check requests.
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
     */
    void updateHealth(double health);
}
