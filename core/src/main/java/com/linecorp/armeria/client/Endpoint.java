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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import com.google.common.net.HostAndPort;

import com.linecorp.armeria.client.endpoint.EndpointGroupRegistry;

/**
 * A remote endpoint that refers to a single host or a group of multiple hosts.
 *
 * <p>A host endpoint has {@link #host()} and optional {@link #port()} and it can be represented as
 * {@code "<host>"} or {@code "<host>:<port>"} in the authority part of a URI.
 *
 * <p>A group endpoint has {@link #groupName()} and it can be represented as {@code "group:<groupName>"}
 * in the authority part of a URI. It can be resolved into a host endpoint with
 * {@link #resolve(ClientRequestContext)}.
 */
public final class Endpoint {

    /**
     * Parse the authority part of a URI. The authority part may have one of the following formats:
     * <ul>
     *   <li>{@code "group:<groupName>"} for a group endpoint</li>
     *   <li>{@code "<host>:<port>"} for a host endpoint</li>
     *   <li>{@code "<host>"} for a host endpoint with no port number specified</li>
     * </ul>
     */
    public static Endpoint parse(String authority) {
        requireNonNull(authority, "authority");
        if (authority.startsWith("group:")) {
            return ofGroup(authority.substring(6));
        }

        final HostAndPort parsed = HostAndPort.fromString(authority).withDefaultPort(0);
        return new Endpoint(parsed.getHost(), parsed.getPort(), 1000);
    }

    /**
     * Creates a new group {@link Endpoint}.
     */
    public static Endpoint ofGroup(String name) {
        requireNonNull(name, "name");
        return new Endpoint(name);
    }

    /**
     * Creates a new host {@link Endpoint}.
     */
    public static Endpoint of(String host, int port) {
        return of(host, port, 1000);
    }

    /**
     * Creates a new host {@link Endpoint} with unspecified port number.
     */
    public static Endpoint of(String host) {
        return new Endpoint(host, 0, 1000);
    }

    // TODO(trustin): Remove weight and make Endpoint a pure endpoint representation.
    //                We could specify an additional attributes such as weight/priority
    //                when adding an Endpoint to an EndpointGroup.

    /**
     * Creates a new host {@link Endpoint}.
     */
    public static Endpoint of(String host, int port, int weight) {
        requireNonNull(host, "host");
        validatePort("port", port);
        validateWeight(weight);

        return new Endpoint(host, port, weight);
    }

    private final String groupName;
    private final String host;
    private final int port;
    private final int weight;
    private String authority;

    private Endpoint(String groupName) {
        this.groupName = groupName;
        host = null;
        port = 0;
        weight = 0;
    }

    private Endpoint(String host, int port, int weight) {
        this.host = host;
        this.port = port;
        this.weight = weight;
        groupName = null;
    }

    /**
     * Returns {@code true} if this endpoint refers to a group.
     */
    public boolean isGroup() {
        return groupName != null;
    }

    /**
     * Resolves this endpoint into a host endpoint associated with the specified
     * {@link ClientRequestContext}.
     *
     * @return the {@link Endpoint} resolved by {@link EndpointGroupRegistry}.
     *         {@code this} if this endpoint is already a host endpoint.
     */
    public Endpoint resolve(ClientRequestContext ctx) {
        if (isGroup()) {
            return EndpointGroupRegistry.selectNode(ctx, groupName);
        } else {
            return this;
        }
    }

    /**
     * Returns the group name of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a group endpoint
     */
    public String groupName() {
        ensureGroup();
        return groupName;
    }

    /**
     * Returns the host name of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a host endpoint
     */
    public String host() {
        ensureSingle();
        return host;
    }

    /**
     * Returns the port number of this endpoint.
     *
     * @throws IllegalStateException if this endpoint is not a host endpoint or
     *                               this endpoint does not have its port specified.
     */
    public int port() {
        ensureSingle();
        if (port == 0) {
            throw new IllegalStateException("port not specified");
        }
        return port;
    }

    /**
     * Returns the port number of this endpoint.
     *
     * @param defaultPort the default port number to use when this endpoint does not have its port specified
     *
     * @throws IllegalStateException if this endpoint is not a host endpoint
     */
    public int port(int defaultPort) {
        ensureSingle();
        validatePort("defaultPort", defaultPort);
        return port != 0 ? port : defaultPort;
    }

    /**
     * Returns a new host endpoint with the specified default port number.
     *
     * @return the new endpoint whose port is {@code defaultPort} if this endpoint does not have its port
     *         specified. {@code this} if this endpoint already has its port specified.
     *
     * @throws IllegalStateException if this endpoint is not a host endpoint
     */
    public Endpoint withDefaultPort(int defaultPort) {
        ensureSingle();
        validatePort("defaultPort", defaultPort);

        return port != 0 ? this : new Endpoint(host(), defaultPort, weight());
    }

    /**
     * Returns a new host endpoint with the specified weight.
     *
     * @return the new endpoint with the specified weight. {@code this} if this endpoint has the same weight.
     *
     * @throws IllegalStateException if this endpoint is not a host endpoint
     */
    public Endpoint withWeight(int weight) {
        ensureSingle();
        validateWeight(weight);

        return this.weight == weight ? this : new Endpoint(host(), port, weight);
    }

    /**
     * Returns the weight of this endpoint.
     */
    public int weight() {
        ensureSingle();
        return weight;
    }

    /**
     * Converts this endpoint into the authority part of a URI.
     *
     * @return the authority string
     */
    public String authority() {
        String authority = this.authority;
        if (authority != null) {
            return authority;
        }

        if (isGroup()) {
            authority = "group:" + groupName;
        } else if (port != 0) {
            authority = host() + ':' + port;
        } else {
            authority = host();
        }

        return this.authority = authority;
    }

    private void ensureGroup() {
        if (!isGroup()) {
            throw new IllegalStateException("not a group endpoint");
        }
    }

    private void ensureSingle() {
        if (isGroup()) {
            throw new IllegalStateException("not a host:port endpoint");
        }
    }

    private static void validatePort(String name, int port) {
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException(name + ": " + port + " (expected: 1-65535)");
        }
    }

    private static void validateWeight(int weight) {
        if (weight <= 0) {
            throw new IllegalArgumentException("weight: " + weight + " (expected: > 0)");
        }
    }

    @Override
    public String toString() {
        return "Endpoint(" + authority() + '/' + weight + ')';
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (!(obj instanceof Endpoint)) {
            return false;
        }

        final Endpoint that = (Endpoint) obj;
        if (isGroup()) {
            if (that.isGroup()) {
                return authority().equals(that.authority());
            } else {
                return false;
            }
        } else {
            if (that.isGroup()) {
                return false;
            } else {
                return authority().equals(that.authority()) && weight() == that.weight();
            }
        }
    }

    @Override
    public int hashCode() {
        return authority().hashCode();
    }
}
