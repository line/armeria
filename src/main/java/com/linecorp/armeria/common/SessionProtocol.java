/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Session-level protocol that provides facilities such as framing and flow control.
 */
public enum SessionProtocol {
    /**
     * HTTP (cleartext, HTTP/2 preferred)
     */
    HTTP(false, "http", false, 80),
    /**
     * HTTP over TLS (over TLS, HTTP/2 preferred)
     */
    HTTPS(true, "https", false, 443),
    /**
     * HTTP/1 (over TLS)
     */
    H1(true, "h1", false, 443),
    /**
     * HTTP/1 (cleartext)
     */
    H1C(false, "h1c", false, 80),
    /**
     * HTTP/2 (over TLS)
     */
    H2(true, "h2", true, 443),
    /**
     * HTTP/2 (cleartext)
     */
    H2C(false, "h2c", true, 80);

    private static final Set<SessionProtocol> HTTP_PROTOCOLS = Collections.unmodifiableSet(
            EnumSet.of(HTTP, HTTPS, H1, H1C, H2, H2C));

    /**
     * Returns the set of all known HTTP session protocols. This method is useful when determining if a
     * {@link SessionProtocol} is HTTP or not.
     * e.g. {@code if (SessionProtocol.ofHttp().contains(proto)) { ... }}
     */
    public static Set<SessionProtocol> ofHttp() {
        return HTTP_PROTOCOLS;
    }

    private final boolean useTls;
    private final String uriText;
    private final boolean isMultiplex;
    private final int defaultPort;

    SessionProtocol(boolean useTls, String uriText, boolean isMultiplex, int defaultPort) {
        this.useTls = useTls;
        this.uriText = uriText;
        this.isMultiplex = isMultiplex;
        this.defaultPort = defaultPort;
    }

    /**
     * Returns {@code true} if and only if this protocol uses TLS as its transport-level security layer.
     */
    public boolean isTls() {
        return useTls;
    }

    /**
     * Returns the textual representation of this format for use in a {@link Scheme}.
     */
    public String uriText() {
        return uriText;
    }

    /**
     * Returns {@code true} if and only if this protocol can multiplex a single transport-layer connection into
     * more than one stream.
     */
    public boolean isMultiplex() {
        return isMultiplex;
    }

    /**
     * Returns the default INET port number of this protocol.
     */
    public int defaultPort() {
        return defaultPort;
    }
}
