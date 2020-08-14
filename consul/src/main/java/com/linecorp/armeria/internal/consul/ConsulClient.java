/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.internal.consul;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;

/**
 * The Consul Client for accessing to consul agent API server.
 */
public final class ConsulClient {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final ClientOptions retryingClientOptions =
            ClientOptions.of(ClientOptions.DECORATION.newValue(ClientDecoration.of(
                    RetryingClient.newDecorator(RetryRule.failsafe(), 3))));

    private final WebClient webClient;
    private final HealthClient healthClient;

    public ConsulClient(URI uri) {
        this(uri, null);
    }

    public ConsulClient(URI uri, @Nullable String token) {
        final WebClientBuilder builder = WebClient.builder(uri);
        builder.options(retryingClientOptions);
        if (token != null) {
            // TODO(eugene70) test with token
            builder.addHttpHeader("X-Consul-Token", token);
        }
        webClient = builder.build();
        healthClient = HealthClient.of(this);
    }

    /**
     * Returns an {@link ObjectMapper} that is used to encode and decode consul requests and responses.
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Registers a service to Consul Agent with service ID.
     *
     * @param serviceId a service ID that identifying a service
     * @param serviceName a service name to register
     * @param endpoint an endpoint of service to register
     * @param check a check for the service
     * @return a {@link CompletableFuture} that will be completed with the registered service ID
     */
    public HttpResponse register(String serviceId, String serviceName, Endpoint endpoint,
                                 @Nullable Check check) {
        return AgentServiceClient.of(this)
                                 .register(serviceId, serviceName, endpoint.host(), endpoint.port(), check);
    }

    /**
     * De-registers a service to Consul Agent.
     *
     * @param serviceId a service ID that identifying a service
     */
    public HttpResponse deregister(String serviceId) {
        return AgentServiceClient.of(this).deregister(serviceId);
    }

    /**
     * Get registered endpoints with service name from consul agent.
     */
    public CompletableFuture<List<Endpoint>> endpoints(String serviceName) {
        return CatalogClient.of(this).endpoints(serviceName);
    }

    /**
     * Returns the registered endpoints with the specified service name from consul agent.
     */
    public CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName) {
        return healthClient.healthyEndpoints(serviceName);
    }

    /**
     * Returns a {@code WebClient} for accessing to consul server.
     */
    public WebClient consulWebClient() {
        return webClient;
    }

    /**
     * Returns the {@link URI} of consul agent.
     */
    public URI uri() {
        return webClient.uri();
    }
}
