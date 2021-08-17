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

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.PercentEncoder;

/**
 * A Consul client that is responsible for
 * <a href="https://www.consul.io/api/health.html">Health Endpoint API</a>.
 */
final class HealthClient {

    private static final Logger logger = LoggerFactory.getLogger(HealthClient.class);

    private static final String PASSING_PARAM = "passing";

    static HealthClient of(ConsulClient consulClient) {
        return new HealthClient(consulClient);
    }

    private final WebClient client;
    private final ObjectMapper mapper;

    private HealthClient(ConsulClient client) {
        this.client = client.consulWebClient();
        mapper = client.getObjectMapper();
    }

    /**
     * Returns a healthy endpoint list by service name.
     */
    CompletableFuture<List<Endpoint>> healthyEndpoints(String serviceName, @Nullable String datacenter,
                                                       @Nullable String filter) {
        requireNonNull(serviceName, "serviceName");
        final StringBuilder path = new StringBuilder("/health/service/");
        PercentEncoder.encodeComponent(path, serviceName);
        final QueryParamsBuilder paramsBuilder = QueryParams.builder();
        paramsBuilder.add(PASSING_PARAM, "true");
        paramsBuilder.add(ConsulClientUtil.queryParams(datacenter, filter));
        path.append('?').append(paramsBuilder.build().toQueryString());
        return client
                .get(path.toString())
                .aggregate()
                .handle((response, cause) -> {
                    if (cause != null) {
                        logger.warn("Unexpected exception while fetching the registry from Consul: {}" +
                                    " (serviceName: {})", client.uri(), serviceName, cause);
                        return null;
                    }

                    final HttpStatus status = response.status();
                    final String content = response.contentUtf8();
                    if (!status.isSuccess()) {
                        logger.warn("Unexpected response from Consul: {} (status: {}, content: {}," +
                                    " serviceName: {})", client.uri(), status, content, serviceName);
                        return null;
                    }

                    try {
                        return Arrays.stream(mapper.readValue(content, HealthService[].class))
                                     .map(HealthClient::toEndpoint)
                                     .filter(Objects::nonNull)
                                     .collect(toImmutableList());
                    } catch (IOException e) {
                        logger.warn("Unexpected exception while parsing a response from Consul: {}" +
                                    " (content: {}, serviceName: {})", client.uri(), content, serviceName, e);
                        return null;
                    }
                });
    }

    @Nullable
    private static Endpoint toEndpoint(HealthService healthService) {
        if (!Strings.isNullOrEmpty(healthService.service.address)) {
            return Endpoint.of(healthService.service.address, healthService.service.port);
        } else if (!Strings.isNullOrEmpty(healthService.node.address)) {
            return Endpoint.of(healthService.node.address, healthService.service.port);
        } else {
            return null;
        }
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
