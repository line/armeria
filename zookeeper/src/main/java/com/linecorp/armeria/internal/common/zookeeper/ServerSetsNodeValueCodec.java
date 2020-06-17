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
package com.linecorp.armeria.internal.common.zookeeper;

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.endpoint.EndpointGroupException;
import com.linecorp.armeria.common.zookeeper.ServerSetsInstance;

/**
 * A codec for Finagle ServerSets.
 */
public enum ServerSetsNodeValueCodec {
    INSTANCE;

    private static final ObjectMapper mapper = new ObjectMapper().configure(
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final JavaType type = mapper.getTypeFactory().constructType(ServerSetsInstance.class);

    /**
     * Decodes a znode value to a {@link ServerSetsInstance}.
     */
    public ServerSetsInstance decode(byte[] znodeValue) {
        requireNonNull(znodeValue, "znodeValue");
        try {
            return mapper.readValue(znodeValue, type);
        } catch (IOException e) {
            throw new EndpointGroupException("invalid endpoint segment.", e);
        }
    }

    /**
     * Encodes a single {@link ServerSetsInstance} into a byte array representation.
     */
    public byte[] encode(ServerSetsInstance serverSetsInstance) {
        try {
            return mapper.writeValueAsBytes(requireNonNull(serverSetsInstance, "serverSetsInstance"));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to encode serverSetsInstance. serverSetsInstance: " +
                                       serverSetsInstance, e);
        }
    }
}

