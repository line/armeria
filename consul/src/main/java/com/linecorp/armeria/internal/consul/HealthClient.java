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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.InvalidResponseHeadersException;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;

/**
 * Health client supports below APIs.
 * {@code HealthClient} is responsible for endpoint of Consul API:
 * {@code `/health`}(https://www.consul.io/api/health.html)
 *
 * <p>GET /health/node/:node
 * GET /health/checks/:service
 * GET /health/service/:service?passing
 * GET /health/connect/:service
 * GET /health/state/:state
 */
final class HealthClient {

    private static final Logger logger = LoggerFactory.getLogger(HealthClient.class);

    static HealthClient of(ConsulClient consulClient) {
        return new HealthClient(consulClient);
    }

    private final ConsulClient client;
    private final ObjectMapper mapper;

    private HealthClient(ConsulClient client) {
        this.client = client;
        mapper = client.getObjectMapper();
    }

    /**
     * Gets a list of nodes for service.
     */
    private CompletableFuture<List<HealthService>> service(String serviceName, QueryParams params) {
        logger.trace("service() {}, {}", serviceName, params.toQueryString());
        return client.consulWebClient()
                     .get("/health/service/" + serviceName + '?' + params.toQueryString())
                     .aggregate()
                     .thenApply(response -> {
                         logger.trace("response: {}", response);
                         final HttpStatus status = response.status();
                         if (!status.isSuccess()) {
                             throw new CompletionException(
                                     new InvalidResponseHeadersException(response.headers()));
                         }
                         try {
                             return ImmutableList.copyOf(mapper.readValue(
                                     response.content().toStringUtf8(), HealthService[].class));
                         } catch (IOException e) {
                             throw new CompletionException(e);
                         }
                     });
    }

    /**
     * Gets a endpoint list with service name and parameter.
     */
    private CompletableFuture<List<Endpoint>> endpoints(String serviceName, QueryParams params) {
        return service(serviceName, params)
                .thenApply(nodes ->
                                   nodes.stream()
                                        .map(node -> {
                                            final String host;
                                            if (!node.service.address.isEmpty()) {
                                                host = node.service.address;
                                            } else if (!node.node.address.isEmpty()) {
                                                host = node.node.address;
                                            } else {
                                                host = "127.0.0.1";
                                            }
                                            return Endpoint.of(host, node.service.port);
                                        })
                                        .collect(toImmutableList()));
    }

    /**
     * Gets a healthy endpoint list with service name.
     */
    CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName) {
        return endpoints(serviceName, QueryParams.of("passing", true));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(Include.NON_NULL)
    private static final class HealthService {
        @JsonProperty("Node")
        Node node;

        @JsonProperty("Service")
        Service service;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(Include.NON_NULL)
    private static final class Node {
        @JsonProperty("ID")
        String id;

        @JsonProperty("Node")
        String node;

        @JsonProperty("Address")
        String address;

        @JsonProperty("Datacenter")
        String datacenter;

        @JsonProperty("TaggedAddresses")
        Object taggedAddresses;

        @JsonProperty("Meta")
        Map<String, Object> meta;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(Include.NON_NULL)
    public static final class Service {
        @JsonProperty("ID")
        String id;

        @JsonProperty("Service")
        String service;

        @JsonProperty("Tags")
        String[] tags;

        @JsonProperty("Address")
        String address;

        @JsonProperty("TaggedAddresses")
        Object taggedAddresses;

        @JsonProperty("Meta")
        Map<String, Object> meta;

        @JsonProperty("Port")
        int port;

        @JsonProperty("Weights")
        Map<String, Object> weights;
    }
}
