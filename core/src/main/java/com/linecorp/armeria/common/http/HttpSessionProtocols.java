/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.http;

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;

/**
 * HTTP-related {@link SessionProtocol} instances.
 */
public final class HttpSessionProtocols {

    /**
     * HTTP - over TLS, HTTP/2 preferred.
     */
    public static final SessionProtocol HTTPS = SessionProtocol.of("https");

    /**
     * HTTP - cleartext, HTTP/2 preferred.
     */
    public static final SessionProtocol HTTP = SessionProtocol.of("http");

    /**
     * HTTP/1 - over TLS.
     */
    public static final SessionProtocol H1 = SessionProtocol.of("h1");

    /**
     * HTTP/1 - cleartext.
     */
    public static final SessionProtocol H1C = SessionProtocol.of("h1c");

    /**
     * HTTP/2 - over TLS.
     */
    public static final SessionProtocol H2 = SessionProtocol.of("h2");

    /**
     * HTTP/2 - cleartext.
     */
    public static final SessionProtocol H2C = SessionProtocol.of("h2c");

    private static final Set<SessionProtocol> HTTP_PROTOCOLS = ImmutableSet.of(HTTPS, HTTP, H1, H1C, H2, H2C);

    /**
     * Returns the set of all known HTTP {@link SessionProtocol}s.
     */
    public static Set<SessionProtocol> values() {
        return HTTP_PROTOCOLS;
    }

    /**
     * Returns whether the specified {@link SessionProtocol} is HTTP.
     */
    public static boolean isHttp(SessionProtocol protocol) {
        return values().contains(requireNonNull(protocol, "protocol"));
    }

    private HttpSessionProtocols() {}
}
