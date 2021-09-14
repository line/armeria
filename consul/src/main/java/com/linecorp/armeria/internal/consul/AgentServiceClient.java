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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.PercentEncoder;

/**
 * A Consul client that is responsible for
 * <a href="https://www.consul.io/api/agent/service.html">Agent HTTP API</a>.
 */
final class AgentServiceClient {

    static AgentServiceClient of(ConsulClient consulClient) {
        return new AgentServiceClient(consulClient);
    }

    private final WebClient client;
    private final ObjectMapper mapper;

    private AgentServiceClient(ConsulClient client) {
        this.client = client.consulWebClient();
        mapper = client.getObjectMapper();
    }

    /**
     * Registers a service into the Consul agent.
     */
    HttpResponse register(String serviceId, String serviceName, String address, int port,
                          @Nullable Check check, List<String> tags) {
        final Service service = new Service(serviceId, serviceName, address, port, check, tags);
        try {
            return client.put("/agent/service/register", mapper.writeValueAsString(service));
        } catch (JsonProcessingException e) {
            return HttpResponse.ofFailure(e);
        }
    }

    /**
     * De-registers a service from the Consul agent.
     */
    HttpResponse deregister(String serviceId) {
        requireNonNull(serviceId, "serviceId");
        final StringBuilder path = new StringBuilder("/agent/service/deregister/");
        PercentEncoder.encodeComponent(path, serviceId);
        return client.put(path.toString(), HttpData.empty());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(Include.NON_NULL)
    private static final class Service {

        @JsonProperty("ID")
        private final String id;

        @JsonProperty("Name")
        private final String name;

        @JsonProperty("Address")
        private final String address;

        @JsonProperty("Port")
        private final int port;

        @Nullable
        @JsonProperty("Check")
        private final Check check;

        @JsonProperty("Tags")
        private final List<String> tags;

        Service(String id, String name, String address, int port, @Nullable Check check, List<String> tags) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.port = port;
            this.check = check;
            this.tags = tags;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .omitNullValues()
                              .add("id", id)
                              .add("name", name)
                              .add("address", address)
                              .add("port", port)
                              .add("check", check)
                              .add("tags", tags)
                              .toString();
        }
    }
}
