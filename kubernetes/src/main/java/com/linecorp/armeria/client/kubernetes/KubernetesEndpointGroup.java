/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.kubernetes;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;

import io.netty.channel.EventLoop;

/**
 * A {@link DynamicEndpointGroup} that fetches node IPs and ports for each Pod from Kubernetes.
 */
public final class KubernetesEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(KubernetesEndpointGroup.class);

    private final KubernetesClient client;
    private final String namespace;
    private final String serviceName;
    private final long registryFetchIntervalMillis;

    public static KubernetesEndpointGroup of(String k8sApiUri, String namespace, String serviceName) {
        return builder(k8sApiUri)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    public static KubernetesEndpointGroup ofToken(String k8sApiUri, String namespace, String serviceName,
                                                  AuthToken token) {
        return builder(k8sApiUri)
                .namespace(namespace)
                .serviceName(serviceName)
                .auth(token)
                .build();
    }

    /**
     * Easy client creation, follows this plan
     *
     * <ul>
     *   <li>If $KUBECONFIG is defined, use that config file.
     *   <li>If $HOME/.kube/config can be found, use that.
     *   <li>If the in-cluster service account can be found, assume in cluster config.
     *   <li>Default to localhost:8080 as a last resort.
     * </ul>
     *
     * @return The best APIClient given the previously described rules
     */
    public static KubernetesEndpointGroup ofDefault(String serviceName) {
        // TODO(ikhoon): Fork KubeConfig from kubernetes-client-java and use it here.
        return builder("...")
                .serviceName(serviceName)
                .build();
    }

    public static KubernetesEndpointGroup ofConfig(Path k8sConfigPath, String namespace, String serviceName) {
        // TODO(ikhoon): Fork KubeConfig from kubernetes-client-java and use it here.
        return null;
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified {@code k8sApiUri}.
     */
    public static KubernetesEndpointGroupBuilder builder(String k8sApiUri) {
        return builder(URI.create(k8sApiUri));
    }

    /**
     * Returns a newly created {@link KubernetesEndpointGroupBuilder} with the specified {@link URI}.
     */
    public static KubernetesEndpointGroupBuilder builder(URI k8sApiUri) {
        return new KubernetesEndpointGroupBuilder(k8sApiUri);
    }

    KubernetesEndpointGroup(WebClient webClient, String namespace, String serviceName,
                            EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                            long selectionTimeoutMillis, long registryFetchIntervalMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        client = new KubernetesClient(webClient);
        this.namespace = namespace;
        this.serviceName = serviceName;
        this.registryFetchIntervalMillis = registryFetchIntervalMillis;
        start(null);
    }

    private void start(@Nullable EventLoop eventLoop) {
        if (isClosing()) {
            return;
        }

        CompletableFuture<List<Endpoint>> endpointsFuture;
        if (eventLoop == null) {
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                endpointsFuture = getEndpoints();
                eventLoop = captor.get().eventLoop().withoutContext();
            }
        } else {
            endpointsFuture = getEndpoints();
        }

        final EventLoop finalEventLoop = eventLoop;
        endpointsFuture.handle((endpoints, cause) -> {
            if (isClosing()) {
                return null;
            }

            if (cause != null || endpoints.isEmpty()) {
                final String message = "Failed to fetch endpoints from Kubernetes API: {} " +
                                       "(namespace: {}, serviceName: {})";
                if (cause != null) {
                    logger.warn(message, client.uri(), namespace, serviceName, cause);
                } else {
                    logger.warn(message, client.uri(), namespace, serviceName);
                }
            } else {
                setEndpoints(endpoints);
            }

            finalEventLoop.schedule(() -> start(finalEventLoop),
                                    registryFetchIntervalMillis, TimeUnit.MILLISECONDS);
            return null;
        });
    }

    CompletableFuture<List<Endpoint>> getEndpoints() {
        final CompletableFuture<Integer> nodePortFuture = client.getNodePort(namespace, serviceName);

        return client.getPods(namespace, serviceName).thenCompose(podNames -> {
            final List<CompletableFuture<String>> nodesFuture =
                    podNames.stream()
                            .map(name -> client.getNode(namespace, name))
                            .collect(toImmutableList());

            return CompletableFutures.successfulAsList(nodesFuture, cause -> {
                logger.warn("Failed to fetch node names from Kubernetes API: {} " +
                            "(namespace: {}, serviceName: {})", client.uri(), namespace, serviceName, cause);
                return null;
            }).thenCompose(nodeNames -> {
                final List<CompletableFuture<List<String>>> ipFuture =
                        nodeNames.stream()
                                 .filter(Objects::nonNull)
                                 .distinct()
                                 .map(client::getInternalIps)
                                 .collect(toImmutableList());
                if (ipFuture.isEmpty()) {
                    throw new IllegalStateException("No nodes found for service: " + serviceName);
                }

                return CompletableFutures.successfulAsList(ipFuture, cause -> {
                    logger.warn("Failed to fetch IP addresses from Kubernetes API: {} " +
                                "(namespace: {}, serviceName: {})", client.uri(), namespace, serviceName,
                                cause);
                    return null;
                }).thenCompose(ips -> {
                    return nodePortFuture.thenApply(nodePort -> {
                        return ips.stream()
                                  .filter(Objects::nonNull)
                                  .flatMap(List::stream)
                                  .map(ip -> Endpoint.of(ip, nodePort))
                                  .collect(toImmutableList());
                    });
                });
            });
        });
    }
}
