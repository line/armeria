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
package com.linecorp.armeria.client.endpoint.zookeeper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroupException;

/**
 * Default {@link ZooKeeperNodeValueConverter} implementation which assumes zNode value is a comma-separated
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
public class DefaultZooKeeperNodeValueConverter implements ZooKeeperNodeValueConverter {

    private static final Pattern SEGMENT_DELIMITER = Pattern.compile("\\s*,\\s*");

    @Override
    public List<Endpoint> convert(byte[] data) {
        List<Endpoint> endpointsList = new ArrayList<>();
        String valueString = new String(data, StandardCharsets.UTF_8);
        try {
            for (String segment : SEGMENT_DELIMITER.split(valueString)) {
                final String[] tokens = segment.split(":");
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
                                "invalid endpoint list: " + segment + '/' + valueString);
                }

                endpointsList.add(endpoint);
            }
        } catch (EndpointGroupException e) {
            throw e;
        } catch (Exception e) {
            throw new EndpointGroupException("invalid endpoint list: " + valueString, e);
        }

        if (endpointsList.isEmpty()) {
            throw new EndpointGroupException("ZNode does not contain any endpoints.");
        }

        return endpointsList;
    }
}
