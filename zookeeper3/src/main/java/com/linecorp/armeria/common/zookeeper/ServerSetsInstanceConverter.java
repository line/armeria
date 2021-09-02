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
package com.linecorp.armeria.common.zookeeper;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

final class ServerSetsInstanceConverter {

    private static final String SERVICE_ENDPOINT = "serviceEndpoint";
    private static final String HOST = "host";
    private static final String PORT = "port";
    private static final String ADDITIONAL_ENDPOINTS = "additionalEndpoints";
    private static final String STATUS = "status";
    private static final String ALIVE = "ALIVE";
    private static final String SHARD = "shard";
    private static final String METADATA = "metadata";

    static final class FinagleServiceInstanceSerializer extends StdSerializer<ServerSetsInstance> {

        private static final long serialVersionUID = 4497981752858570527L;

        FinagleServiceInstanceSerializer() {
            super(ServerSetsInstance.class);
        }

        @Override
        public void serialize(ServerSetsInstance value, JsonGenerator gen, SerializerProvider provider)
                throws IOException {
            gen.writeStartObject();
            gen.writeObjectFieldStart(SERVICE_ENDPOINT);
            final Endpoint serviceEndpoint = value.serviceEndpoint();
            assert serviceEndpoint != null;
            writeEndpoint(gen, serviceEndpoint);
            gen.writeEndObject();
            gen.writeObjectFieldStart(ADDITIONAL_ENDPOINTS);
            for (Entry<String, Endpoint> additionalEndpoint : value.additionalEndpoints().entrySet()) {
                gen.writeObjectFieldStart(additionalEndpoint.getKey());
                writeEndpoint(gen, additionalEndpoint.getValue());
                gen.writeEndObject();
            }
            gen.writeEndObject();
            // The status from ServerSets will be removed so we always use "ALIVE".
            // See https://github.com/twitter/finagle/blob/finagle-20.5.0/finagle-serversets/src/main/thrift/com/twitter/thrift/endpoint.thrift#L100
            gen.writeStringField(STATUS, ALIVE);
            @Nullable
            final Integer shardId = value.shardId();
            if (shardId != null) {
                gen.writeNumberField(SHARD, shardId);
            }
            final Map<String, String> metadata = value.metadata();
            gen.writeFieldName(METADATA);
            gen.writeStartObject();
            if (!metadata.isEmpty()) {
                for (Entry<String, String> entry : metadata.entrySet()) {
                    gen.writeStringField(entry.getKey(), entry.getValue());
                }
            }
            gen.writeEndObject(); // end for metadata
            gen.writeEndObject();
        }

        private static void writeEndpoint(JsonGenerator gen, Endpoint serviceEndpoint) throws IOException {
            gen.writeStringField(HOST, serviceEndpoint.host());
            gen.writeNumberField(PORT, serviceEndpoint.port());
        }
    }

    static final class FinagleServiceInstanceDeserializer extends StdDeserializer<ServerSetsInstance> {

        private static final long serialVersionUID = 3445603112141405710L;

        private static final Logger logger = LoggerFactory.getLogger(FinagleServiceInstanceDeserializer.class);

        private static final ServerSetsInstance notAliveInstance = new ServerSetsInstance(
          null, ImmutableMap.of(), null, ImmutableMap.of());

        FinagleServiceInstanceDeserializer() {
            super(ServerSetsInstance.class);
        }

        @Override
        public ServerSetsInstance deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException, JsonProcessingException {
            final JsonNode tree = p.getCodec().readTree(p);
            final JsonNode serviceEndpointNode = tree.get(SERVICE_ENDPOINT);
            final Endpoint serviceEndpoint = endpoint(serviceEndpointNode);
            final String status = tree.get(STATUS).asText();
            if (!ALIVE.equals(status)) {
                logger.warn("Found an instance whose status is not alive. status: {}, serviceEndpoint: {}",
                            status, serviceEndpoint);
                return notAliveInstance;
            }
            final ImmutableMap.Builder<String, Endpoint> additionalsBuilder = ImmutableMap.builder();
            final Iterator<Entry<String, JsonNode>> additionals = tree.get(ADDITIONAL_ENDPOINTS).fields();
            while (additionals.hasNext()) {
                final Entry<String, JsonNode> next = additionals.next();
                additionalsBuilder.put(next.getKey(), endpoint(next.getValue()));
            }

            final JsonNode shardNode = tree.get(SHARD);
            @Nullable
            final Integer shardId = shardNode == null ? null : shardNode.asInt();

            final ImmutableMap.Builder<String, String> metadataBuilder = ImmutableMap.builder();
            final JsonNode metadataNode = tree.get(METADATA);
            if (metadataNode != null) {
                final Iterator<Entry<String, JsonNode>> fields = metadataNode.fields();
                while (fields.hasNext()) {
                    final Entry<String, JsonNode> next = fields.next();
                    metadataBuilder.put(next.getKey(), next.getValue().asText());
                }
            }

            return new ServerSetsInstance(serviceEndpoint, additionalsBuilder.build(),
                                          shardId, metadataBuilder.build());
        }

        private static Endpoint endpoint(JsonNode serviceEndpointNode) {
            return Endpoint.of(serviceEndpointNode.get(HOST).asText(),
                               serviceEndpointNode.get(PORT).asInt());
        }
    }

    private ServerSetsInstanceConverter() {}
}
