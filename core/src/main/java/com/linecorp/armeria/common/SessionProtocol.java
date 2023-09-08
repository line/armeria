/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.common;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Set;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Session-level protocol that provides facilities such as framing and flow control.
 */
public enum SessionProtocol {
    /**
     * HTTP - over TLS, HTTP/2 preferred.
     */
    HTTPS("https", true, false, 443),
    /**
     * HTTP - cleartext, HTTP/2 preferred.
     */
    HTTP("http", false, false, 80),
    /**
     * HTTP/1 - over TLS.
     */
    H1("h1", true, false, 443),
    /**
     * HTTP/1 - cleartext.
     */
    H1C("h1c", false, false, 80),
    /**
     * HTTP/2 - over TLS.
     */
    H2("h2", true, true, 443),
    /**
     * HTTP/2 - cleartext.
     */
    H2C("h2c", false, true, 80),
    /**
     * <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">PROXY protocol</a> - v1 or v2.
     */
    PROXY("proxy", false, false, 0);

    private static final Set<SessionProtocol> HTTP_VALUES = Sets.immutableEnumSet(HTTP, H1C, H2C);

    private static final Set<SessionProtocol> HTTPS_VALUES = Sets.immutableEnumSet(HTTPS, H1, H2);

    private static final Set<SessionProtocol> HTTP_AND_HTTPS_VALUES =
            Sets.immutableEnumSet(HTTPS, HTTP, H1, H1C, H2, H2C);

    private static final Map<String, SessionProtocol> uriTextToProtocols;

    static {
        final ImmutableMap.Builder<String, SessionProtocol> builder = ImmutableMap.builder();
        for (SessionProtocol e : values()) {
            builder.put(e.uriText(), e);
        }

        uriTextToProtocols = builder.build();
    }

    /**
     * Returns the {@link SessionProtocol} with the specified {@link #uriText()}.
     *
     * @throws IllegalArgumentException if there's no such {@link SessionProtocol}
     */
    public static SessionProtocol of(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        final SessionProtocol value = uriTextToProtocols.get(uriText);
        checkArgument(value != null, "unknown session protocol: %s", uriText);
        return value;
    }

    /**
     * Finds the {@link SessionProtocol} with the specified {@link #uriText()}.
     */
    @Nullable
    public static SessionProtocol find(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        return uriTextToProtocols.get(uriText);
    }

    /**
     * Returns an immutable {@link Set} that contains {@link #HTTP}, {@link #H1C} and {@link #H2C}.
     * Note that it does not contain HTTPS protocols such as {@link #HTTPS}, {@link #H1} and {@link #H2}.
     *
     * @see #httpsValues()
     */
    public static Set<SessionProtocol> httpValues() {
        return HTTP_VALUES;
    }

    /**
     * Returns an immutable {@link Set} that contains {@link #HTTPS}, {@link #H1} and {@link #H2}.
     * Note that it does not contain HTTP protocols such as {@link #HTTP}, {@link #H1C} and {@link #H2C}.
     *
     * @see #httpValues()
     */
    public static Set<SessionProtocol> httpsValues() {
        return HTTPS_VALUES;
    }

    /**
     * Returns an immutable {@link Set} that contains {@link #httpValues()} and {@link #httpsValues()}.
     */
    @UnstableApi
    public static Set<SessionProtocol> httpAndHttpsValues() {
        return HTTP_AND_HTTPS_VALUES;
    }

    private final String uriText;
    private final boolean useTls;
    private final boolean isMultiplex;
    private final int defaultPort;

    SessionProtocol(String uriText, boolean useTls, boolean isMultiplex, int defaultPort) {
        this.useTls = useTls;
        this.uriText = uriText;
        this.isMultiplex = isMultiplex;
        this.defaultPort = defaultPort;
    }

    /**
     * Returns {@code true} if this {@link SessionProtocol} is one of {@link #HTTP}, {@link #H1C} and
     * {@link #H2C}.
     *
     * @see #httpValues()
     */
    public boolean isHttp() {
        return HTTP_VALUES.contains(this);
    }

    /**
     * Returns {@code true} if this {@link SessionProtocol} is one of {@link #HTTPS}, {@link #H1} and
     * {@link #H2}.
     *
     * @see #httpsValues()
     */
    public boolean isHttps() {
        return HTTPS_VALUES.contains(this);
    }

    /**
     * Returns {@code true} if this {@link SessionProtocol} is {@link #H1} or {@link #H1C}.
     * Note that this method returns {@code false} for {@link #HTTP} and {@link #HTTPS}.
     */
    public boolean isExplicitHttp1() {
        return this == H1 || this == H1C;
    }

    /**
     * Returns {@code true} if this {@link SessionProtocol} is {@link #H2} or {@link #H2C}.
     * Note that this method returns {@code false} for {@link #HTTP} and {@link #HTTPS}.
     */
    public boolean isExplicitHttp2() {
        return this == H2 || this == H2C;
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

    @Override
    public String toString() {
        return uriText;
    }
}
