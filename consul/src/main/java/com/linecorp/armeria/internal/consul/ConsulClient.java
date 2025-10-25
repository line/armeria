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
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;

/**
 * A client for accessing a Consul agent API server.
 */
public final class ConsulClient {
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final Function<? super HttpClient, RetryingClient> retryingClientDecorator =
            RetryingClient.newDecorator(RetryConfig.builder(RetryRule.failsafe())
                                                   .maxTotalAttempts(3)
                                                   .build());

    private static final CharSequence X_CONSUL_TOKEN = HttpHeaderNames.of("x-consul-token");

    public static ConsulClientBuilder builder(URI consulUri) {
        return new ConsulClientBuilder(consulUri);
    }

    private final WebClient webClient;
    private final AgentServiceClient agentClient;
    private final CatalogClient catalogClient;
    private final HealthClient healthClient;

    ConsulClient(URI uri, @Nullable String token) {
        final WebClientBuilder builder = WebClient.builder(uri)
                                                  .decorator(retryingClientDecorator);
        if (token != null) {
            builder.addHeader(X_CONSUL_TOKEN, token);
        }
        webClient = builder.build();
        agentClient = AgentServiceClient.of(this);
        catalogClient = CatalogClient.of(this);
        healthClient = HealthClient.of(this);
    }

    /**
     * Returns an {@link ObjectMapper} that is used to encode and decode Consul requests and responses.
     */
    ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    /**
     * Registers a service to Consul Agent.
     *
     * @param serviceId a service ID that identifying a service
     * @param serviceName a service name to register
     * @param endpoint an endpoint of service to register
     * @param check a check for the service
     * @param tags tags for the service
     *
     * @return an HttpResponse representing the HTTP response from Consul
     */
    public HttpResponse register(String serviceId, String serviceName, Endpoint endpoint,
                                 @Nullable Check check, List<String> tags) {
        return agentClient.register(serviceId, serviceName, endpoint.host(), endpoint.port(), check, tags);
    }

    /**
     * De-registers a service from Consul Agent.
     *
     * @param serviceId a service ID that identifying a service
     *
     * @return an HttpResponse representing the HTTP response from Consul
     */
    public HttpResponse deregister(String serviceId) {
        return agentClient.deregister(serviceId);
    }

    /**
     * Retrieves the list of registered endpoints for the specified service name from the Consul agent.
     *
     * @param serviceName the name of the service whose endpoints are to be retrieved
     *
     * @return a {@link CompletableFuture} which provides a list of {@link Endpoint}s
     */
    public CompletableFuture<List<Endpoint>> endpoints(String serviceName) {
        return endpoints(serviceName, null, null);
    }

    /**
     * Retrieves the list of registered endpoints for the specified service name and datacenter
     * from the Consul agent, optionally applying a filter.
     *
     * @param serviceName the name of the service whose endpoints are to be retrieved
     * @param datacenter the datacenter to query; if {@code null}, the default datacenter is used
     * @param filter a filter expression to apply; if {@code null}, no filtering is performed
     *
     * @return a {@link CompletableFuture} which provides a list of {@link Endpoint}s
     */
    public CompletableFuture<List<Endpoint>> endpoints(String serviceName, @Nullable String datacenter,
                                                       @Nullable String filter) {
        return catalogClient.endpoints(serviceName, datacenter, filter);
    }

    /**
     * Retrieves the list of healthy endpoints for the specified service name from the Consul agent.
     *
     * @param serviceName the name of the service whose healthy endpoints are to be retrieved
     *
     * @return a {@link CompletableFuture} which provides a list of healthy {@link Endpoint}s
     */
    public CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName) {
        return healthyEndpoints(serviceName, null, null);
    }

    /**
     * Retrieves the list of healthy endpoints for the specified service name and datacenter
     * from the Consul agent, optionally applying a filter.
     *
     * @param serviceName the name of the service whose healthy endpoints are to be retrieved
     * @param datacenter the datacenter to query; if {@code null}, the default datacenter is used
     * @param filter a filter expression to apply; if {@code null}, no filtering is performed
     *
     * @return a {@link CompletableFuture} which provides a list of healthy {@link Endpoint}s
     */
    public CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName, @Nullable String datacenter,
                                                              @Nullable String filter) {
        return healthClient.healthyEndpoints(serviceName, datacenter, filter);
    }

    /**
     * Returns a {@code WebClient} for accessing to Consul server.
     */
    WebClient consulWebClient() {
        return webClient;
    }

    /**
     * Returns the {@link URI} of Consul agent.
     */
    public URI uri() {
        return webClient.uri();
    }
}
