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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpEntity;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A Nacos client that is responsible for
 * <a href="https://nacos.io/en-us/docs/v2/guide/user/open-api.html">Nacos Open-Api - Query instances</a>.
 */
final class QueryInstancesClient {

    static QueryInstancesClient of(NacosClient nacosClient, @Nullable LoginClient loginClient,
                                   String nacosApiVersion) {
        return new QueryInstancesClient(nacosClient, loginClient, nacosApiVersion);
    }

    private final WebClient webClient;

    @Nullable
    private final LoginClient loginClient;

    private final String nacosApiVersion;

    QueryInstancesClient(NacosClient nacosClient, @Nullable LoginClient loginClient,
                         String nacosApiVersion) {
        webClient = nacosClient.nacosWebClient();
        this.loginClient = loginClient;
        this.nacosApiVersion = nacosApiVersion;
    }

    CompletableFuture<List<Endpoint>> endpoints(String serviceName, @Nullable String namespaceId,
                                                @Nullable String groupName, @Nullable String clusterName,
                                                @Nullable Boolean healthyOnly, @Nullable String app) {
        requireNonNull(serviceName, "serviceName");
        return queryInstances(serviceName, namespaceId, groupName, clusterName, healthyOnly, app)
                .thenApply(response -> response.data.hosts.stream()
                        .map(QueryInstancesClient::toEndpoint)
                        .filter(Objects::nonNull)
                        .collect(toImmutableList()));
    }

    CompletableFuture<QueryInstancesResponse> queryInstances(String serviceName, @Nullable String namespaceId,
                                                 @Nullable String groupName, @Nullable String clusterName,
                                                 @Nullable Boolean healthyOnly, @Nullable String app) {
        requireNonNull(serviceName, "serviceName");
        final StringBuilder path = new StringBuilder("/").append(nacosApiVersion).append("/ns/instance/list?");
        final QueryParamsBuilder paramsBuilder = NacosClientUtil
                .queryParamsBuilder(namespaceId, groupName, serviceName, clusterName, healthyOnly, app, null,
                                    null, null);
        path.append(paramsBuilder.build().toQueryString());
        if (loginClient == null) {
            return webClient.prepare()
                    .get(path.toString())
                    .asJson(QueryInstancesResponse.class)
                    .as(HttpEntity::content)
                    .execute();
        } else {
            return loginClient.login().thenCompose(accessToken ->
                    webClient.prepare()
                            .get(path.append("&accessToken=").append(accessToken).toString())
                            .asJson(QueryInstancesResponse.class)
                            .as(HttpEntity::content)
                            .execute()
            );
        }
    }

    @Nullable
    private static Endpoint toEndpoint(Host host) {
        if (host.enabled != null && !host.enabled) {
            return null;
        } else if (host.weight != null && host.weight.intValue() >= 0) {
            return Endpoint.of(host.ip, host.port).withWeight(host.weight.intValue());
        } else {
            return Endpoint.of(host.ip, host.port);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class QueryInstancesResponse {
        Data data;

        @JsonCreator
        QueryInstancesResponse(@JsonProperty("data") Data data) {
            this.data = data;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Data {
        List<Host> hosts;

        @JsonCreator
        Data(@JsonProperty("hosts") List<Host> hosts) {
            this.hosts = hosts;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class Host {
        @Nullable
        String instanceId;

        String ip;

        Integer port;

        @Nullable
        Double weight;

        @Nullable
        Boolean healthy;

        @Nullable
        Boolean enabled;

        @Nullable
        Boolean ephemeral;

        @Nullable
        String clusterName;

        @Nullable
        String serviceName;

        @Nullable
        Map<String, Object> metadata;

        @Nullable
        Integer instanceHeartBeatInterval;

        @Nullable
        String instanceIdGenerator;

        @Nullable
        Integer instanceHeartBeatTimeOut;

        @Nullable
        Integer ipDeleteTimeout;

        @JsonCreator
        Host(@JsonProperty("instanceId") @Nullable String instanceId, @JsonProperty("ip") String ip,
             @JsonProperty("port") Integer port, @JsonProperty("weight") @Nullable Double weight,
             @JsonProperty("healthy") @Nullable Boolean healthy,
             @JsonProperty("enabled") @Nullable Boolean enabled,
             @JsonProperty("ephemeral") @Nullable Boolean ephemeral,
             @JsonProperty("clusterName") @Nullable String clusterName,
             @JsonProperty("serviceName") @Nullable String serviceName,
             @JsonProperty("metadata") @Nullable Map<String, Object> metadata,
             @JsonProperty("instanceHeartBeatInterval") @Nullable Integer instanceHeartBeatInterval,
             @JsonProperty("instanceIdGenerator") @Nullable String instanceIdGenerator,
             @JsonProperty("instanceHeartBeatTimeOut") @Nullable Integer instanceHeartBeatTimeOut,
             @JsonProperty("ipDeleteTimeout") @Nullable Integer ipDeleteTimeout
        ) {
            this.instanceId = instanceId;
            this.ip = ip;
            this.port = port;
            this.weight = weight;
            this.healthy = healthy;
            this.enabled = enabled;
            this.ephemeral = ephemeral;
            this.clusterName = clusterName;
            this.serviceName = serviceName;
            this.metadata = metadata;
            this.instanceHeartBeatInterval = instanceHeartBeatInterval;
            this.instanceIdGenerator = instanceIdGenerator;
            this.instanceHeartBeatTimeOut = instanceHeartBeatTimeOut;
            this.ipDeleteTimeout = ipDeleteTimeout;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                    .omitNullValues()
                    .add("instanceId", instanceId)
                    .add("ip", ip)
                    .add("port", port)
                    .add("weight", weight)
                    .add("healthy", healthy)
                    .add("enabled", enabled)
                    .add("ephemeral", ephemeral)
                    .add("clusterName", clusterName)
                    .add("serviceName", serviceName)
                    .add("metaData", metadata)
                    .add("instanceHeartBeatInterval", instanceHeartBeatInterval)
                    .add("instanceIdGenerator", instanceIdGenerator)
                    .add("instanceHeartBeatTimeOut", instanceHeartBeatTimeOut)
                    .add("ipDeleteTimeout", ipDeleteTimeout)
                    .toString();
        }
    }
}
