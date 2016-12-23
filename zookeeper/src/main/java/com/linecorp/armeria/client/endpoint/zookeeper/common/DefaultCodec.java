/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.client.endpoint.zookeeper.common;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * Default {@link Codec} implementation which assumes zNode value is a comma-separated
 * string. Each element of the zNode value represents an endpoint whose format is
 * {@code <host>[:<port_number>[:weight]]}, such as:
 * <ul>
 *   <li>{@code "foo.com"} - default port number, default weight (1000)</li>
 *   <li>{@code "bar.com:8080} - port number 8080, default weight (1000)</li>
 *   <li>{@code "10.0.2.15:0:500} - default port number, weight 500</li>
 *   <li>{@code "192.168.1.2:8443:700} - port number 8443, weight 700</li>
 * </ul>
 * Note that the port number must be specified to specify a weight.
 */
public class DefaultCodec implements Codec {

    private final String segmentDelimiter;
    private final String fieldDelimiter;

    /**
     * Create a ZooKeeper node value codec.
     * @param segmentDelimiter delimiter of segment
     * @param fieldDelimiter   delimiter of fields
     */
    public DefaultCodec(String segmentDelimiter, String fieldDelimiter) {
        this.segmentDelimiter = requireNonNull(segmentDelimiter, "segmentDelimiter");
        this.fieldDelimiter = requireNonNull(fieldDelimiter, "fieldDelimiter");
    }

    /**
     * Create a default codec with segment delimiter ',' and filed delimiter ':'.
     */
    public DefaultCodec() {
        segmentDelimiter = ",";
        fieldDelimiter = ":";
    }

    @Override
    public Endpoint decode(byte[] zNodeValue) {
        final String segment = new String(zNodeValue, StandardCharsets.UTF_8);
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
    public Set<Endpoint> decodeAll(byte[] data) {
        Set<Endpoint> endpoints = new HashSet<>();
        String valueString = new String(data, StandardCharsets.UTF_8);
        final Pattern SEGMENT_DELIMITER = Pattern.compile("\\s*" + segmentDelimiter + "\\s*");
        try {
            for (String segment : SEGMENT_DELIMITER.split(valueString)) {
                endpoints.add(decode(segment.getBytes(StandardCharsets.UTF_8)));
            }
        } catch (EndpointGroupException e) {
            throw e;
        } catch (Exception e) {
            throw new EndpointGroupException("invalid endpoint list: " + valueString, e);
        }
        if (endpoints.isEmpty()) {
            throw new EndpointGroupException("ZNode does not contain any endpoints.");
        }

        return endpoints;
    }

    @Override
    public byte[] encodeAll(Set<Endpoint> endpoints) {
        requireNonNull(endpoints, "endpoints");
        StringBuffer nodeValue = new StringBuffer();
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
