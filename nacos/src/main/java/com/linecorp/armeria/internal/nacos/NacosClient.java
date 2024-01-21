/*
 * Copyright 2024 LINE Corporation
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
package com.linecorp.armeria.internal.nacos;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.WebClientBuilder;
import com.linecorp.armeria.client.retry.RetryConfig;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;

public final class NacosClient {

    private static final Function<? super HttpClient, RetryingClient> retryingClientDecorator =
            RetryingClient.newDecorator(RetryConfig.builder(RetryRule.onServerErrorStatus())
                    .maxTotalAttempts(3)
                    .build());

    public static NacosClientBuilder builder(URI nacosUri) {
        return new NacosClientBuilder(nacosUri);
    }

    private final WebClient webClient;

    private final QueryInstancesClient queryInstancesClient;

    private final RegisterInstanceClient registerInstanceClient;

    NacosClient(URI uri, String nacosApiVersion, @Nullable String username, @Nullable String password) {
        final WebClientBuilder builder = WebClient.builder(uri)
                .decorator(retryingClientDecorator);

        webClient = builder.build();

        final LoginClient loginClient;
        if (username != null && password != null) {
            loginClient = LoginClient.of(this, username, password);
        } else {
            loginClient = null;
        }

        queryInstancesClient = QueryInstancesClient.of(this, loginClient, nacosApiVersion);
        registerInstanceClient = RegisterInstanceClient.of(this, loginClient, nacosApiVersion);
    }

    public CompletableFuture<List<Endpoint>> endpoints(String serviceName, @Nullable String namespaceId,
                                                       @Nullable String groupName, @Nullable String clusterName,
                                                       @Nullable Boolean healthyOnly, @Nullable String app) {
        return queryInstancesClient.endpoints(serviceName, namespaceId, groupName,
                clusterName, healthyOnly, app);
    }

    /**
     * Registers a instance to Nacos with service name.
     *
     * @param serviceName a service name that identifying a service.
     * @param endpoint an endpoint of service to register
     * @param namespaceId the namespace ID of the service instance.
     * @param groupName the group name of the service.
     * @param clusterName the cluster name of the service.
     * @param app the application name associated with the service.
     * @return a {@link HttpResponse} indicating the result of the registration operation.
     */
    public HttpResponse register(String serviceName, Endpoint endpoint, @Nullable String namespaceId,
                                 @Nullable String groupName, @Nullable String clusterName,
                                 @Nullable String app) {
        return registerInstanceClient.register(serviceName, endpoint.host(), endpoint.port(), endpoint.weight(),
                                               namespaceId, groupName, clusterName, app);
    }

    /**
     * De-registers a instance to Nacos with service name.
     *
     * @param serviceName a service name that identifying a service.
     * @param endpoint an endpoint of service to register
     * @param namespaceId the namespace ID of the service instance.
     * @param groupName the group name of the service.
     * @param clusterName the cluster name of the service.
     * @param app the application name associated with the service.
     * @return a {@link HttpResponse} indicating the result of the de-registration operation.
     */
    public HttpResponse deregister(String serviceName, Endpoint endpoint, @Nullable String namespaceId,
                                   @Nullable String groupName, @Nullable String clusterName,
                                   @Nullable String app) {
        return registerInstanceClient.deregister(serviceName, endpoint.host(), endpoint.port(),
                                                 endpoint.weight(), namespaceId, groupName, clusterName, app);
    }

    /**
     * Returns a {@code WebClient} for accessing to Nacos server.
     */
    WebClient nacosWebClient() {
        return webClient;
    }

    /**
     * Returns the {@link URI} of Nacos uri.
     */
    public URI uri() {
        return webClient.uri();
    }
}
