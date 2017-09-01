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
package com.linecorp.armeria.common.zookeeper;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

final class DefaultNodeValueCodec implements NodeValueCodec {
    static final DefaultNodeValueCodec INSTANCE = new DefaultNodeValueCodec();
    private static final String segmentDelimiter = ",";
    private static final String fieldDelimiter = ":";
    private static final Pattern SEGMENT_DELIMITER = Pattern.compile("\\s*" + segmentDelimiter + "\\s*");

    @Override
    public Endpoint decode(String segment) {
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
                    endpoint = Endpoint.of(host, port, weight);
                }
                break;
            }
            default: //unknown
                throw new EndpointGroupException(
                        "invalid endpoint list: " + segment);
        }
        return endpoint;
    }

    @Override
    public Set<Endpoint> decodeAll(String valueString) {
        Set<Endpoint> endpoints = new HashSet<>();
        try {
            for (String segment : SEGMENT_DELIMITER.split(valueString)) {
                endpoints.add(decode(segment));
            }
        } catch (Exception e) {
            throw new EndpointGroupException("invalid endpoint list: " + valueString, e);
        }
        if (endpoints.isEmpty()) {
            throw new EndpointGroupException("ZNode does not contain any endpoints.");
        }
        return endpoints;
    }

    @Override
    public byte[] encodeAll(Iterable<Endpoint> endpoints) {
        requireNonNull(endpoints, "endpoints");
        StringBuilder nodeValue = new StringBuilder();
        endpoints.forEach(endpoint -> nodeValue.append(endpoint.host()).append(fieldDelimiter).append(
                endpoint.port()).append(fieldDelimiter).append(endpoint.weight()).append(segmentDelimiter));
        //delete the last unused segment delimiter
        if (nodeValue.length() > 0) {
            nodeValue.deleteCharAt(nodeValue.length() - 1);
        }
        return nodeValue.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] encode(Endpoint endpoint) {
        return (endpoint.host() + fieldDelimiter + endpoint.port() + fieldDelimiter + endpoint.weight())
                .getBytes(StandardCharsets.UTF_8);
    }
}
