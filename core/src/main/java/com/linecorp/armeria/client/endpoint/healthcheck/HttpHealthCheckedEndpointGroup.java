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

import java.net.StandardProtocolFamily;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientBuilder;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.SessionProtocol;

/**
 * HTTP implementation of {@link HealthCheckedEndpointGroup}.
 */
public final class HttpHealthCheckedEndpointGroup extends HealthCheckedEndpointGroup {

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    public static HttpHealthCheckedEndpointGroup of(EndpointGroup delegate,
                                                    String healthCheckPath) {
        return new HttpHealthCheckedEndpointGroupBuilder(delegate, healthCheckPath).build();
    }

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     *
     * @deprecated use {@link HttpHealthCheckedEndpointGroupBuilder}
     */
    @Deprecated
    public static HttpHealthCheckedEndpointGroup of(EndpointGroup delegate,
                                                    String healthCheckPath,
                                                    Duration healthCheckRetryInterval) {
        return of(ClientFactory.DEFAULT, delegate, healthCheckPath, healthCheckRetryInterval);
    }

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     *
     * @deprecated use {@link HttpHealthCheckedEndpointGroupBuilder}
     */
    @Deprecated
    public static HttpHealthCheckedEndpointGroup of(ClientFactory clientFactory,
                                                    EndpointGroup delegate,
                                                    String healthCheckPath,
                                                    Duration healthCheckRetryInterval) {
        return new HttpHealthCheckedEndpointGroupBuilder(delegate, healthCheckPath)
                .clientFactory(clientFactory)
                .retryInterval(healthCheckRetryInterval)
                .build();
    }

    private final SessionProtocol protocol;
    private final String healthCheckPath;
    private final Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator;

    /**
     * Creates a new {@link HttpHealthCheckedEndpointGroup} instance.
     */
    HttpHealthCheckedEndpointGroup(ClientFactory clientFactory,
                                   EndpointGroup delegate,
                                   SessionProtocol protocol,
                                   String healthCheckPath,
                                   Duration healthCheckRetryInterval,
                                   Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {
        super(clientFactory, delegate, healthCheckRetryInterval);
        this.protocol = requireNonNull(protocol, "protocol");
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");
        this.configurator = requireNonNull(configurator, "configurator");
        init();
    }

    @Override
    protected EndpointHealthChecker createEndpointHealthChecker(Endpoint endpoint) {
        return new HttpEndpointHealthChecker(clientFactory(), endpoint, protocol, healthCheckPath,
                                             configurator);
    }

    private static final class HttpEndpointHealthChecker implements EndpointHealthChecker {
        private final HttpClient httpClient;
        private final String healthCheckPath;

        private HttpEndpointHealthChecker(
                ClientFactory clientFactory, Endpoint endpoint,
                SessionProtocol protocol, String healthCheckPath,
                Function<? super ClientOptionsBuilder, ClientOptionsBuilder> configurator) {

            final String scheme = protocol.uriText();
            final String ipAddr = endpoint.ipAddr();
            final HttpClientBuilder builder;
            if (ipAddr == null) {
                builder = new HttpClientBuilder(scheme + "://" + endpoint.authority());
            } else {
                final int port = endpoint.port(protocol.defaultPort());
                if (endpoint.ipFamily() == StandardProtocolFamily.INET) {
                    builder = new HttpClientBuilder(scheme + "://" + ipAddr + ':' + port);
                } else {
                    builder = new HttpClientBuilder(scheme + "://[" + ipAddr + "]:" + port);
                }
                builder.setHttpHeader(HttpHeaderNames.AUTHORITY, endpoint.authority());
            }

            httpClient = builder.factory(clientFactory)
                                .options(configurator.apply(new ClientOptionsBuilder()).build())
                                .build();
            this.healthCheckPath = healthCheckPath;
        }

        @Override
        public CompletableFuture<Boolean> isHealthy(Endpoint endpoint) {
            return httpClient.get(healthCheckPath)
                             .aggregate()
                             .thenApply(message -> HttpStatus.OK.equals(message.status()));
        }
    }
}
