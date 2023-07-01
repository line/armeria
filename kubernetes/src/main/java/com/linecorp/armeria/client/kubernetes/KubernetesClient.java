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
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.RestClient;
import com.linecorp.armeria.client.WebClient;

final class KubernetesClient {

    private final RestClient client;

    KubernetesClient(WebClient client) {
        this.client = client.asRestClient();
    }

    URI uri() {
        return client.uri();
    }

    /**
     * Fetches the pod names of the service.
     */
    CompletableFuture<List<String>> getPods(String namespace, String serviceName) {
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
    CompletableFuture<String> getNode(String namespace, String podName) {
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
    CompletableFuture<List<String>> getInternalIps(String nodeName) {
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
    CompletableFuture<Integer> getNodePort(String namespace, String serviceName) {
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
