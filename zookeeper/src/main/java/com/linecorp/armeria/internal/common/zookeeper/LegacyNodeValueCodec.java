/*
 * Copyright 2016 LINE Corporation
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

import java.nio.charset.StandardCharsets;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * The legacy codec.
 */
public enum LegacyNodeValueCodec {
    INSTANCE;

    private static final String fieldDelimiter = ":";

    /**
     * Decodes a znode value to an {@link Endpoint}.
     */
    public Endpoint decode(byte[] znodeValue) {
        requireNonNull(znodeValue, "znodeValue");
        final String segment = new String(znodeValue, StandardCharsets.UTF_8);
        final String[] tokens = segment.split(fieldDelimiter);
        final Endpoint endpoint;
        switch (tokens.length) {
            case 1: //host
                endpoint = Endpoint.of(segment);
                break;
            case 2: { //host and port
                final String host = tokens[0];
                final int port = Integer.parseInt(tokens[1]);
                if (port == 0) {
                    endpoint = Endpoint.of(host);
                } else {
                    endpoint = Endpoint.of(host, port);
                }
                break;
            }
            case 3: { //host , port , weight
                final String host = tokens[0];
                final int port = Integer.parseInt(tokens[1]);
                final int weight = Integer.parseInt(tokens[2]);
                if (port == 0) {
                    endpoint = Endpoint.of(host).withWeight(weight);
                } else {
                    endpoint = Endpoint.of(host, port).withWeight(weight);
                }
                break;
            }
            default: //unknown
                throw new EndpointGroupException("invalid endpoint segment: " + segment);
        }
        return endpoint;
    }

    /**
     * Encodes a single {@link Endpoint} into a byte array representation.
     */
    public byte[] encode(Endpoint endpoint) {
        requireNonNull(endpoint, "endpoint");
        final String endpointStr;
        if (endpoint.hasPort()) {
            endpointStr = endpoint.host() + fieldDelimiter + endpoint.port() +
                          fieldDelimiter + endpoint.weight();
        } else {
            endpointStr = endpoint.host();
        }
        return endpointStr.getBytes(StandardCharsets.UTF_8);
    }
}
