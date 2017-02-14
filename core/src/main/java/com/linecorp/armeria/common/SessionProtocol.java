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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;

import com.google.common.base.Ascii;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableBiMap;

import com.linecorp.armeria.common.http.HttpSessionProtocols;

/**
 * Session-level protocol that provides facilities such as framing and flow control.
 */
public final class SessionProtocol implements Comparable<SessionProtocol> {

    private static final BiMap<String, SessionProtocol> uriTextToProtocols;
    private static final Set<SessionProtocol> values;

    static {
        // Load all session protocols from the providers.
        final BiMap<String, SessionProtocol> mutableUriTextToProtocols = HashBiMap.create();
        ServiceLoader.load(SessionProtocolProvider.class, SessionProtocolProvider.class.getClassLoader())
                     .forEach(provider -> provider.entries().forEach(e -> {
                         checkState(!mutableUriTextToProtocols.containsKey(e.uriText),
                                    "session protocol registered already: ", e.uriText);

                         mutableUriTextToProtocols.put(e.uriText,
                                 new SessionProtocol(e.uriText, e.useTls, e.isMultiplex, e.defaultPort));
                     }));

        uriTextToProtocols = ImmutableBiMap.copyOf(mutableUriTextToProtocols);
        values = uriTextToProtocols.values();
    }

    /**
     * @deprecated Use {@link HttpSessionProtocols#HTTPS} instead.
     */
    @Deprecated
    public static final SessionProtocol HTTPS = HttpSessionProtocols.HTTPS;

    /**
     * @deprecated Use {@link HttpSessionProtocols#HTTP}.
     */
    @Deprecated
    public static final SessionProtocol HTTP = HttpSessionProtocols.HTTP;

    /**
     * @deprecated Use {@link HttpSessionProtocols#H1}.
     */
    @Deprecated
    public static final SessionProtocol H1 = HttpSessionProtocols.H1;

    /**
     * @deprecated Use {@link HttpSessionProtocols#H1C}.
     */
    @Deprecated
    public static final SessionProtocol H1C = HttpSessionProtocols.H1C;

    /**
     * @deprecated Use {@link HttpSessionProtocols#H2}.
     */
    @Deprecated
    public static final SessionProtocol H2 = HttpSessionProtocols.H2;

    /**
     * @deprecated Use {@link HttpSessionProtocols#H2C}.
     */
    @Deprecated
    public static final SessionProtocol H2C = HttpSessionProtocols.H2C;

    /**
     * @deprecated Use {@link HttpSessionProtocols#values()}.
     */
    @Deprecated
    public static Set<SessionProtocol> ofHttp() {
        return HttpSessionProtocols.values();
    }

    /**
     * Returns all available {@link SessionProtocol}s.
     */
    public static Set<SessionProtocol> values() {
        return values;
    }

    /**
     * Returns the {@link SessionProtocol} with the specified {@link #uriText()}.
     *
     * @throws IllegalArgumentException if there's no such {@link SessionProtocol}
     */
    public static SessionProtocol of(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        final SessionProtocol value = uriTextToProtocols.get(uriText);
        checkArgument(value != null, "unknown session protocol: ", uriText);
        return value;
    }

    /**
     * Finds the {@link SessionProtocol} with the specified {@link #uriText()}.
     */
    public static Optional<SessionProtocol> find(String uriText) {
        uriText = Ascii.toLowerCase(requireNonNull(uriText, "uriText"));
        return Optional.ofNullable(uriTextToProtocols.get(uriText));
    }

    private final String uriText;
    private final boolean useTls;
    private final boolean isMultiplex;
    private final int defaultPort;

    private SessionProtocol(String uriText, boolean useTls, boolean isMultiplex, int defaultPort) {
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

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj;
    }

    @Override
    public int compareTo(SessionProtocol o) {
        return uriText.compareTo(o.uriText);
    }

    @Override
    public String toString() {
        return uriText;
    }
}
