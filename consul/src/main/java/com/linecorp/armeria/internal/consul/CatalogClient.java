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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A Consul client that is responsible for
 * <a href="https://www.consul.io/api/catalog.html">Catalog HTTP API</a>.
 */
public final class CatalogClient {

    private static final CollectionType collectionTypeForNode =
            TypeFactory.defaultInstance().constructCollectionType(List.class, Node.class);

    static CatalogClient of(ConsulClient consulClient) {
        return new CatalogClient(consulClient);
    }

    private final ConsulClient client;
    private final ObjectMapper mapper;

    private CatalogClient(ConsulClient client) {
        this.client = client;
        mapper = client.getObjectMapper();
    }

    /**
     * Gets endpoint list with service name.
     */
    CompletableFuture<List<Endpoint>> endpoints(String serviceName) {
        requireNonNull(serviceName, "serviceName");
        return service(serviceName)
                .thenApply(nodes -> {
                    final Function<Node, Endpoint> nodeEndpointFunction = node -> {
                        final String host;
                        if (!Strings.isNullOrEmpty(node.serviceAddress)) {
                            host = node.serviceAddress;
                        } else if (!Strings.isNullOrEmpty(node.address)) {
                            host = node.address;
                        } else {
                            host = "127.0.0.1";
                        }
                        return Endpoint.of(host, node.servicePort);
                    };
                    return nodes.stream()
                                .map(nodeEndpointFunction)
                                .collect(toImmutableList());
                });
    }

    /**
     * Returns node list with service name.
     */
    @VisibleForTesting
    CompletableFuture<List<Node>> service(String serviceName) {
        requireNonNull(serviceName, "serviceName");

        return client.consulWebClient()
                     .get("/catalog/service/" + serviceName)
                     .aggregate()
                     .thenApply(response -> {
                         try {
                             return mapper.readValue(response.content().toStringUtf8(), collectionTypeForNode);
                         } catch (JsonProcessingException e) {
                             return Exceptions.throwUnsafely(e);
                         }
                    });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static final class Node {
        @Nullable
        @JsonProperty("ID")
        String id;

        @Nullable
        @JsonProperty("Node")
        String node;

        @Nullable
        @JsonProperty("Address")
        String address;

        @Nullable
        @JsonProperty("Datacenter")
        String datacenter;

        @Nullable
        @JsonProperty("TaggedAddresses")
        Map<String, String> taggedAddresses;

        @Nullable
        @JsonProperty("NodeMeta")
        Map<String, Object> nodeMeta;

        @JsonProperty("CreateIndex")
        int createIndex;

        @JsonProperty("ModifyIndex")
        int modifyIndex;

        @JsonProperty("ServiceAddress")
        String serviceAddress;

        @JsonProperty("ServiceEnableTagOverride")
        boolean serviceEnableTagOverride;

        @Nullable
        @JsonProperty("ServiceID")
        String serviceId;

        @Nullable
        @JsonProperty("ServiceName")
        String serviceName;

        @JsonProperty("ServicePort")
        int servicePort;

        @Nullable
        @JsonProperty("ServiceMeta")
        Map<String, Object> serviceMeta;

        @Nullable
        @JsonProperty("ServiceTaggedAddresses")
        Map<String, Object> serviceTaggedAddresses;

        @Nullable
        @JsonProperty("ServiceTags")
        String[] serviceTags;

        @Nullable
        @JsonProperty("ServiceProxy")
        Map<String, Object> serviceProxy;

        @Nullable
        @JsonProperty("ServiceConnect")
        Map<String, Object> serviceConnect;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("id", id)
                              .add("node", node)
                              .add("address", address)
                              .add("datacenter", datacenter)
                              .add("taggedAddresses", taggedAddresses)
                              .add("nodeMeta", nodeMeta)
                              .add("createIndex", createIndex)
                              .add("modifyIndex", modifyIndex)
                              .add("serviceAddress", serviceAddress)
                              .add("serviceEnableTagOverride", serviceEnableTagOverride)
                              .add("serviceId", serviceId)
                              .add("serviceName", serviceName)
                              .add("servicePort", servicePort)
                              .add("serviceMeta", serviceMeta)
                              .add("serviceTaggedAddresses", serviceTaggedAddresses)
                              .add("serviceTags", serviceTags)
                              .add("serviceProxy", serviceProxy)
                              .add("serviceConnect", serviceConnect)
                              .toString();
        }
    }
}
