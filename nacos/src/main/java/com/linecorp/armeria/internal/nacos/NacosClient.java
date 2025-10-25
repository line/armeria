/*
 * Copyright 2024 LY Corporation

 * LY Corporation licenses this file to you under the Apache License,
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
package com.linecorp.armeria.internal.nacos;

import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;

public final class NacosClient {

    private static final Function<? super HttpClient, RetryingClient> retryingClientDecorator =
            RetryingClient.newDecorator(RetryConfig.builder(RetryRule.onServerErrorStatus())
                                                   .maxTotalAttempts(3)
                                                   .build());

    public static NacosClientBuilder builder(URI nacosUri, String serviceName) {
        return new NacosClientBuilder(nacosUri, serviceName);
    }

    private final URI uri;

    private final QueryInstancesClient queryInstancesClient;

    private final RegisterInstanceClient registerInstanceClient;

    NacosClient(URI uri, String nacosApiVersion, @Nullable String username, @Nullable String password,
                String serviceName, @Nullable String namespaceId, @Nullable String groupName,
                @Nullable String clusterName, @Nullable Boolean healthyOnly, @Nullable String app) {
        this.uri = uri;

        final WebClientBuilder builder = WebClient.builder(uri)
                                                  .decorator(retryingClientDecorator);
        if (username != null && password != null) {
            builder.decorator(LoginClient.newDecorator(builder.build(), username, password));
        }

        final WebClient webClient = builder.build();

        queryInstancesClient = QueryInstancesClient.of(webClient, nacosApiVersion, serviceName, namespaceId,
                                                       groupName, clusterName, healthyOnly, app);
        registerInstanceClient = RegisterInstanceClient.of(webClient, nacosApiVersion, serviceName, namespaceId,
                                                           groupName, clusterName, app);
    }

    public CompletableFuture<List<Endpoint>> endpoints() {
        return queryInstancesClient.endpoints();
    }

    /**
     * Registers an instance to Nacos with service name.
     *
     * @return a {@link HttpResponse} indicating the result of the registration operation.
     */
    public HttpResponse register(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return registerInstanceClient.register(endpoint.host(), endpoint.port(), endpoint.weight());
    }

    /**
     * De-registers an instance to Nacos with service name.
     *
     * @return a {@link HttpResponse} indicating the result of the de-registration operation.
     */
    public HttpResponse deregister(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        return registerInstanceClient.deregister(endpoint.host(), endpoint.port(), endpoint.weight());
    }

    /**
     * Returns the {@link URI} of Nacos uri.
     */
    public URI uri() {
        return uri;
    }
}
