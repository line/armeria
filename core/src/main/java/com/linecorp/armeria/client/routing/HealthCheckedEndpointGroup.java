/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.routing;

import static com.linecorp.armeria.common.util.Functions.voidFunction;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.IntStream;

import com.google.common.collect.ImmutableList;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.http.HttpClient;
import com.linecorp.armeria.common.http.AggregatedHttpMessage;
import com.linecorp.armeria.common.http.HttpStatus;

import jp.skypencil.guava.stream.GuavaCollectors;

/**
 * An {@link EndpointGroup} decorator that only provides healthy {@link Endpoint}s.
 */
public final class HealthCheckedEndpointGroup implements EndpointGroup {
    private final ClientFactory clientFactory;
    private final EndpointGroup delegate;
    private final String healthCheckPath;
    volatile List<ServerConnection> allServers = ImmutableList.of();
    volatile List<ServerConnection> healthyServers = ImmutableList.of();

    /**
     * Creates a new instance.
     */
    public HealthCheckedEndpointGroup(ClientFactory clientFactory,
                                      EndpointGroup delegate,
                                      String healthCheckPath) {
        this.clientFactory = requireNonNull(clientFactory, "clientFactory");
        this.delegate = requireNonNull(delegate, "delegate");
        this.healthCheckPath = requireNonNull(healthCheckPath, "healthCheckPath");

        checkAndUpdateHealthyServers();
        clientFactory.eventLoopGroup().scheduleWithFixedDelay(this::checkAndUpdateHealthyServers,
                                                              /* initialDelay */ 3,
                                                              /* delay */ 3,
                                                              TimeUnit.SECONDS);
    }

    /**
     * Update the servers this health checker client talks to.
     */
    private List<ServerConnection> updateServerList() {
        Map<Endpoint, ServerConnection> allServersByEndpoint = allServers
                .stream()
                .collect(GuavaCollectors.toImmutableMap(ServerConnection::endpoint,
                                                        Function.identity()));
        return allServers = delegate
                .endpoints()
                .stream()
                .map(server -> {
                    ServerConnection connection = allServersByEndpoint.get(server);
                    return connection != null ? connection : createConnection(server);
                })
                .collect(GuavaCollectors.toImmutableList());
    }

    private void checkAndUpdateHealthyServers() {
        List<ServerConnection> checkedServers = updateServerList();

        CompletableFuture<List<AggregatedHttpMessage>> healthCheckResults = CompletableFutures.successfulAsList(
                checkedServers.stream()
                              .map(this::checkServerHealth)
                              .collect(GuavaCollectors.toImmutableList()),
                t -> null);
        healthCheckResults.handle(voidFunction((result, thrown) -> {
            healthyServers = IntStream
                    .range(0, result.size())
                    .filter(i -> result.get(i) != null && result.get(i).status().equals(HttpStatus.OK))
                    .mapToObj(checkedServers::get)
                    .collect(GuavaCollectors.toImmutableList());
        })).join();
    }

    private CompletableFuture<AggregatedHttpMessage> checkServerHealth(ServerConnection serverConnection) {
        return serverConnection.healthCheckClient.get(healthCheckPath).aggregate();
    }

    private ServerConnection createConnection(Endpoint endpoint) {
        HttpClient healthCheckClient = Clients.newClient(clientFactory,
                                                         "none+http://" + endpoint.authority(),
                                                         HttpClient.class);
        return new ServerConnection(endpoint, healthCheckClient);
    }

    @Override
    public List<Endpoint> endpoints() {
        return healthyServers.stream()
                             .map(ServerConnection::endpoint)
                             .collect(GuavaCollectors.toImmutableList());
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("HealthCheckedEndpointGroup(all:[");
        for (ServerConnection connection : allServers) {
            buf.append(connection.endpoint).append(',');
        }
        buf.setCharAt(buf.length() - 1, ']');
        buf.append(", healthy:[");
        for (ServerConnection connection : healthyServers) {
            buf.append(connection.endpoint).append(',');
        }
        buf.setCharAt(buf.length() - 1, ']');
        buf.append(')');
        return buf.toString();
    }

    static final class ServerConnection {
        private final Endpoint endpoint;
        private final HttpClient healthCheckClient;

        private ServerConnection(Endpoint endpoint, HttpClient healthCheckClient) {
            this.endpoint = endpoint;
            this.healthCheckClient = healthCheckClient;
        }

        Endpoint endpoint() {
            return endpoint;
        }

        @Override
        public String toString() {
            return "ServerConnection(endpoint:" + endpoint + ')';
        }

        @Override
        public boolean equals(Object obj) {
            if (!(obj instanceof ServerConnection)) {
                return false;
            }
            if (this == obj) {
                return true;
            }
            return endpoint().equals(((ServerConnection) obj).endpoint());
        }

        @Override
        public int hashCode() {
            return endpoint().hashCode();
        }
    }
}
