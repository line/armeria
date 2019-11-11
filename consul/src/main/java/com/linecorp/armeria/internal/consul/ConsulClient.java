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
package com.linecorp.armeria.internal.consul;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.ClientDecoration;
import com.linecorp.armeria.client.ClientOption;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.QueryParams;

/**
 * The Consul Client for accessing to consul agent API server.
 */
public final class ConsulClient {
    /**
     * Default Consul API URI.
     */
    @VisibleForTesting
    static final String DEFAULT_URI = "http://localhost:8500/v1";

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final ClientOptions retryingClientOptions =
            ClientOptions.of(ClientOption.DECORATION.newValue(ClientDecoration.of(
                    RetryingClient.newDecorator(RetryRule.failsafe(), 3))));

    private final WebClient webClient;
    private final HealthClient healthClient;

    public ConsulClient(@Nullable String uri) {
        this(uri, null);
    }

    public ConsulClient(@Nullable String uri, @Nullable String token) {
        final WebClientBuilder builder = WebClient.builder(uri == null ? DEFAULT_URI : uri);
        builder.options(retryingClientOptions);
        if (token != null) {
            // TODO(eugene70) test with token
            builder.addHttpHeader("X-Consul-Token", token);
        }
        webClient = builder.build();
        healthClient = HealthClient.of(this);
    }

    /**
     * Gets a object mapper.
     * @return Jackson ObjectMapper
     */
    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Registers a service to Consul Agent without service ID.
     *
     * @param serviceName service name
     * @param endpoint servicing endpoint
     * @param check a check for health checking
     * @return CompletableFuture with registered service ID(auto-generated)
     */
    public CompletableFuture<String> register(String serviceName, Endpoint endpoint, @Nullable Check check)
            throws JsonProcessingException {
        return AgentServiceClient.of(this)
                                 .register(serviceName, endpoint.host(), endpoint.port(), check,
                                           QueryParams.of());
    }

    /**
     * Registers a service to Consul Agent with service ID.
     *
     * @param serviceId a service ID that identifying a service
     * @param serviceName a service name to register
     * @param endpoint an endpoint of service to register
     * @param check a check for the service
     * @return CompletableFuture with registered service ID
     */
    public CompletableFuture<String> register(String serviceId, String serviceName, Endpoint endpoint,
                                              @Nullable Check check) throws JsonProcessingException {
        return AgentServiceClient.of(this)
                                 .register(serviceId, serviceName, endpoint.host(), endpoint.port(), check,
                                           QueryParams.of());
    }

    /**
     * De-registers a service to Consul Agent.
     *
     * @param serviceId a service ID that identifying a service
     */
    public CompletableFuture<Void> deregister(String serviceId) {
        return AgentServiceClient.of(this).deregister(serviceId);
    }

    /**
     * Get registered endpoints with service name from consul agent.
     */
    public CompletableFuture<List<Endpoint>> endpoints(String serviceName) {
        return CatalogClient.of(this)
                            .endpoints(serviceName, QueryParams.of());
    }

    /**
     * Get registered endpoints with service name from consul agent.
     */
    public CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName) {
        return healthClient.healthyEndpoints(serviceName);
    }

    /**
     * Gets a {@code WebClient} for accessing to consul server.
     */
    public WebClient consulWebClient() {
        return webClient;
    }

    /**
     * Gets a URL of consul agent.
     */
    public String url() {
        return webClient.uri().toString();
    }
}
