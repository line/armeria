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
package com.linecorp.armeria.client.routing;

import static java.util.Objects.requireNonNull;

public class DefaultWeightedEndpoint implements WeightedEndpoint {

    private static final int DEFAULT_WEIGHT = 1;

    private final String hostname;

    private final int port;

    private final int weight;

    public DefaultWeightedEndpoint(String hostname, int port) {
        this(hostname, port, DEFAULT_WEIGHT);
    }

    public DefaultWeightedEndpoint(String hostname, int port, int weight) {
        requireNonNull(hostname, "hostname");

        this.hostname = hostname;
        this.port = port;
        this.weight = weight;
    }

    @Override
    public int weight() {
        return weight;
    }

    @Override
    public String hostname() {
        return hostname;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        DefaultWeightedEndpoint node = (DefaultWeightedEndpoint) o;

        return port == node.port && hostname.equals(node.hostname);
    }

    @Override
    public int hashCode() {
        return 31 * hostname.hashCode() + port;
    }

    @Override
    public String toString() {
        StringBuilder buf = new StringBuilder();
        buf.append("DefaultWeightedEndpoint(");
        buf.append(hostname).append(':').append(port);
        buf.append(',').append(weight).append(')');
        return buf.toString();
    }
}
