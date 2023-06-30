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

package com.linecorp.armeria.client.endpoint;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.RestClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.AuthToken;

import io.netty.channel.EventLoop;

/**
 * A {@link DynamicEndpointGroup} that fetches node IPs and ports for each Pod from Kubernetes.
 */
public final class K8sEndpointGroup extends DynamicEndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(K8sEndpointGroup.class);

    private final RestClient client;
    private final String namespace;
    private final String serviceName;
    private final long registryFetchIntervalMillis;

    public static K8sEndpointGroup of(String k8sApiUri, String namespace, String serviceName) {
        return builder(k8sApiUri)
                .namespace(namespace)
                .serviceName(serviceName)
                .build();
    }

    public static K8sEndpointGroup ofToken(String k8sApiUri, String namespace, String serviceName,
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
    public static K8sEndpointGroup ofDefault(String serviceName) {
        // TODO(ikhoon): Fork KubeConfig from kubernetes-client-java and use it here.
        return builder("...")
                .serviceName(serviceName)
                .build();
    }

    public static K8sEndpointGroup ofConfig(Path k8sConfigPath, String namespace, String serviceName) {
        // TODO(ikhoon): Fork KubeConfig from kubernetes-client-java and use it here.
        return null;
    }

    /**
     * Returns a newly created {@link K8sEndpointGroupBuilder} with the specified {@code k8sApiUri}.
     */
    public static K8sEndpointGroupBuilder builder(String k8sApiUri) {
        return builder(URI.create(k8sApiUri));
    }

    /**
     * Returns a newly created {@link K8sEndpointGroupBuilder} with the specified {@link URI}.
     */
    public static K8sEndpointGroupBuilder builder(URI k8sApiUri) {
        new K8sEndpointGroupBuilder(k8sApiUri);
    }

    K8sEndpointGroup(WebClient webClient, String namespace, String serviceName,
                     EndpointSelectionStrategy selectionStrategy, boolean allowEmptyEndpoints,
                     long selectionTimeoutMillis, long registryFetchIntervalMillis) {
        super(selectionStrategy, allowEmptyEndpoints, selectionTimeoutMillis);
        client = webClient.asRestClient();
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

            if (cause != null) {
                logger.warn("Failed to fetch endpoints from Kubernetes API: {} " +
                            "(namespace: {}, serviceName: {})", client.uri(), namespace, serviceName, cause);
            } else {
                setEndpoints(endpoints);
            }

            finalEventLoop.schedule(() -> start(finalEventLoop),
                                    registryFetchIntervalMillis, TimeUnit.MILLISECONDS);
            return null;
        });
    }

    CompletableFuture<List<Endpoint>> getEndpoints() {
        final CompletableFuture<Integer> nodePortFuture = getNodePort();

        return getPods().thenCompose(pod -> {
            final List<CompletableFuture<String>> nodesFuture = pod.stream()
                                                                   .map(this::getNode)
                                                                   .collect(toImmutableList());

            return CompletableFutures.successfulAsList(nodesFuture, cause -> null).thenCompose(node -> {
                final List<CompletableFuture<List<String>>> ipFuture =
                        node.stream()
                            .filter(Objects::nonNull)
                            .distinct()
                            .map(this::getInternalIps)
                            .collect(toImmutableList());

                return CompletableFutures.successfulAsList(ipFuture, cause -> null).thenCompose(ips -> {
                    return nodePortFuture.thenApply(nodePort -> {
                        return ips.stream()
                                  .flatMap(List::stream)
                                  .map(ip -> Endpoint.of(ip, nodePort))
                                  .collect(toImmutableList());
                    });
                });
            });
        });
    }

    /**
     * Fetches the pod names of the service.
     */
    private CompletableFuture<List<String>> getPods() {
        return client.get("/api/v1/namespaces/:namespace/pods?labelSelector=app%3D" + serviceName)
                     .pathParam("namespace", namespace)
                     .execute(JsonNode.class)
                     .thenApply(response -> {
                         return Streams.stream(response.content().get("items"))
                                       .map(item -> item.get("metadata").get("name").textValue())
                                       .collect(toImmutableList());
                     });
    }

    /**
     * Fetches the node name of the pod.
     */
    private CompletableFuture<String> getNode(String podName) {
        return client.get("/api/v1/namespaces/:namespace/pods/:pod")
                     .pathParam("namespace", namespace)
                     .pathParam("pod", podName)
                     .execute(JsonNode.class)
                     .thenApply(response -> {
                         return response.content().get("spec").get("nodeName").textValue();
                     });
    }

    /**
     * Fetches the internal IPs of the node.
     */
    private CompletableFuture<List<String>> getInternalIps(String nodeName) {
        return client.get("/api/v1/nodes/:nodeName")
                     .pathParam("nodeName", nodeName)
                     .execute(JsonNode.class)
                     .thenApply(response -> {
                         final JsonNode addresses = response.content()
                                                            .get("status")
                                                            .get("addresses");
                         return Streams.stream(addresses)
                                       .filter(address -> "InternalIP".equals(address.get("type").textValue()))
                                       .map(address -> address.get("address").textValue())
                                       .collect(toImmutableList());
                     });
    }

    /**
     * Fetches the node port of the service.
     */
    private CompletableFuture<Integer> getNodePort() {
        return client.get("/api/v1/namespaces/:namespace/services/:serviceName")
                     .pathParam("namespace", namespace)
                     .pathParam("serviceName", serviceName)
                     .execute(JsonNode.class)
                     .thenApply(response -> {
                         return response.content().get("spec")
                                        .get("ports")
                                        .get(0)
                                        .get("nodePort")
                                        .intValue();
                     });
    }
}
