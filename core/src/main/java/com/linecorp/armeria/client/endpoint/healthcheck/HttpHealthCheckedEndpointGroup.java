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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpStatus;

/**
 * HTTP implementation of {@link HealthCheckedEndpointGroup}.
 */
public final class HttpHealthCheckedEndpointGroup extends HealthCheckedEndpointGroup {
    private final String healthCheckPath;

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    public static HttpHealthCheckedEndpointGroup of(EndpointGroup delegate,
                                                    String healthCheckPath) {
        return of(delegate, healthCheckPath, DEFAULT_HEALTHCHECK_RETRY_INTERVAL);
    }

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    public static HttpHealthCheckedEndpointGroup of(EndpointGroup delegate,
                                                    String healthCheckPath,
                                                    Duration healthCheckRetryInterval) {
        return of(ClientFactory.DEFAULT, delegate, healthCheckPath, healthCheckRetryInterval);
    }

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    public static HttpHealthCheckedEndpointGroup of(ClientFactory clientFactory,
                                                    EndpointGroup delegate,
                                                    String healthCheckPath,
                                                    Duration healthCheckRetryInterval) {
        return new HttpHealthCheckedEndpointGroup(clientFactory,
                                                  delegate,
                                                  healthCheckPath,
                                                  healthCheckRetryInterval);
    }

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    private HttpHealthCheckedEndpointGroup(ClientFactory clientFactory,
                                           EndpointGroup delegate,
                                           String healthCheckPath,
                                           Duration healthCheckRetryInterval) {
        super(clientFactory, delegate, healthCheckRetryInterval);
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");
        init();
    }

    @Override
    protected EndpointHealthChecker createEndpointHealthChecker(Endpoint endpoint) {
        return new HttpEndpointHealthChecker(clientFactory(), endpoint, healthCheckPath);
    }

    private static final class HttpEndpointHealthChecker implements EndpointHealthChecker {
        private final HttpClient httpClient;
        private final String healthCheckPath;

        private HttpEndpointHealthChecker(ClientFactory clientFactory,
                                          Endpoint endpoint,
                                          String healthCheckPath) {
            httpClient = HttpClient.of(clientFactory, "http://" + endpoint.authority());
            this.healthCheckPath = healthCheckPath;
        }

        @Override
        public CompletableFuture<Boolean> isHealthy(Endpoint endpoint) {
            return httpClient.get(healthCheckPath)
                             .aggregate()
                             .thenApply(message -> message.status().equals(HttpStatus.OK));
        }
    }
}
