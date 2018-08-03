/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * A scheme and port of a service provider.
 */
final class SamlPortConfig {

    private final SessionProtocol scheme;
    private final int port;

    SamlPortConfig(SessionProtocol scheme, int port) {
        this.scheme = requireNonNull(scheme, "scheme");
        this.port = validatePort(port);
    }

    /**
     * Returns a {@link SessionProtocol} that a service provider is bound to.
     */
    SessionProtocol scheme() {
        return scheme;
    }

    /**
     * Returns a port number that a service provider is bound to.
     */
    int port() {
        return port;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("scheme", scheme)
                          .add("port", port)
                          .toString();
    }

    /**
     * Returns whether the specified {@code port} is in the valid range.
     */
    static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Raises an {@link IllegalArgumentException} if the specified {@code port} is not in the valid range.
     */
    static int validatePort(int port) {
        checkArgument(isValidPort(port), "port: %s (expected: 1-65535)", port);
        return port;
    }
}
