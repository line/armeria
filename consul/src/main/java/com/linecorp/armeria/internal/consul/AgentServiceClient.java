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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpResponse;

/**
 * A Consul client that is responsible for
 * <a href="https://www.consul.io/api/agent/service.html">Agent HTTP API</a>.
 */
final class AgentServiceClient {

    static AgentServiceClient of(ConsulClient consulClient) {
        return new AgentServiceClient(consulClient);
    }

    private final ConsulClient client;
    private final ObjectMapper mapper;

    private AgentServiceClient(ConsulClient client) {
        this.client = client;
        mapper = client.getObjectMapper();
    }

    /**
     * Registers a service into the Consul agent.
     */
    HttpResponse register(String serviceId, String serviceName, String address, int port,
                          @Nullable Check check) {
        final Service service = new Service();
        service.id = serviceId;
        service.name = serviceName;
        service.address = address;
        service.port = port;
        if (check != null) {
            service.check = check;
        }

        try {
            return client.consulWebClient()
                         .put("/agent/service/register",
                              mapper.writeValueAsString(service));
        } catch (JsonProcessingException e) {
            return HttpResponse.ofFailure(e);
        }
    }

    /**
     * De-registers a service from the Consul agent.
     */
    HttpResponse deregister(String serviceId) {
        requireNonNull(serviceId, "serviceId");
        return client.consulWebClient()
                     .put("/agent/service/deregister/" + serviceId, "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(Include.NON_NULL)
    public static final class Service {

        @Nullable
        @JsonProperty("Service")
        String service;

        @Nullable
        @JsonProperty("Name")
        String name;

        @JsonProperty("ID")
        String id;

        @Nullable
        @JsonProperty("Tags")
        String[] tags;

        @Nullable
        @JsonProperty("Address")
        String address;

        @Nullable
        @JsonProperty("TaggedAddresses")
        Map<String, Object> taggedAddresses;

        @Nullable
        @JsonProperty("Meta")
        Map<String, String> meta;

        @JsonProperty("Port")
        int port;

        @Nullable
        @JsonProperty("Kind")
        String kind;

        @Nullable
        @JsonProperty("Proxy")
        Object proxy;

        @Nullable
        @JsonProperty("Connect")
        Object connect;

        @Nullable
        @JsonProperty("Check")
        Check check;

        @Nullable
        @JsonProperty("Checks")
        List<Check> checks;

        @JsonProperty("EnableTagOverride")
        boolean enableTagOverride;

        @Nullable
        @JsonProperty("Weights")
        Map<String, Object> weights;

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("service", service)
                              .add("name", name)
                              .add("id", id)
                              .add("tags", tags)
                              .add("address", address)
                              .add("taggedAddresses", taggedAddresses)
                              .add("meta", meta)
                              .add("port", port)
                              .add("kind", kind)
                              .add("proxy", proxy)
                              .add("connect", connect)
                              .add("check", check)
                              .add("checks", checks)
                              .add("enableTagOverride", enableTagOverride)
                              .add("weights", weights)
                              .toString();
        }
    }
}
