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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import javax.annotation.Nonnull;

import com.linecorp.armeria.client.routing.EndpointGroupRegistry;

public final class Endpoint {

    public static Endpoint parse(String authority) {
        requireNonNull(authority, "authority");
        if (authority.startsWith("group:")) {
            return ofGroup(authority.substring(6));
        }

        final int lastColonIdx = authority.lastIndexOf(':');
        if (lastColonIdx <= 0) {
            throw parseFailure(authority);
        }

        final String host = authority.substring(0, lastColonIdx);
        final int port;
        try {
            port = Integer.parseInt(authority.substring(lastColonIdx + 1));
        } catch (NumberFormatException ignored) {
            throw parseFailure(authority);
        }

        if (port <= 0 || port >= 65536) {
            throw parseFailure(authority);
        }

        return of(host, port);
    }

    @Nonnull
    private static IllegalArgumentException parseFailure(String authority) {
        return new IllegalArgumentException("cannot find 'group:' nor valid ':<port>': " + authority);
    }

    public static Endpoint ofGroup(String name) {
        requireNonNull(name, "name");
        return new Endpoint(name);
    }

    public static Endpoint of(String host, int port) {
        return of(host, port, 1000);
    }

    // TODO(trustin): Remove weight and make Endpoint a pure endpoint representation.
    //                We could specify an additional attributes such as weight/priority
    //                when adding an Endpoint to an EndpointGroup.

    public static Endpoint of(String host, int port, int weight) {
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

    public boolean isGroup() {
        return groupName != null;
    }

    public Endpoint resolve() {
        if (isGroup()) {
            return EndpointGroupRegistry.selectNode(groupName);
        } else {
            return this;
        }
    }

    public String groupName() {
        ensureGroup();
        return groupName;
    }

    public String host() {
        ensureSingle();
        return host;
    }

    public int port() {
        ensureSingle();
        return port;
    }

    public int weight() {
        ensureSingle();
        return weight;
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

    public String authority() {
        String authority = this.authority;
        if (authority != null) {
            return authority;
        }

        if (isGroup()) {
            authority = "group:" + groupName;
        } else {
            authority = host() + ':' + port();
        }

        return this.authority = authority;
    }

    @Override
    public String toString() {
        return "Endpoint(" + authority() + ')';
    }
}
