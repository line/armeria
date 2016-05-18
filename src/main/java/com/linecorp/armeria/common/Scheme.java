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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * A pair of {@link SerializationFormat} and {@link SessionProtocol}.
 * <p>
 * A {@link Scheme} is represented and used as the scheme of a URI in the following format:
 * </p>
 * <pre>{@code
 * SerializationFormat.uriText() + '+' + SessionProtocol.uriText()
 * }</pre>
 * <p>
 * For example:
 * </p>
 * <ul>
 * <li>{@code "tbinary+https"} - {@link SerializationFormat#THRIFT_BINARY} and {@link SessionProtocol#HTTPS}</li>
 * <li>{@code "tcompact+h2c"} - {@link SerializationFormat#THRIFT_COMPACT} and {@link SessionProtocol#H2C}</li>
 * </ul>
 */
public final class Scheme implements Comparable<Scheme> {

    private static final Map<String, Scheme> SCHEMES;

    static {
        // Pre-populate all known scheme combos.
        final Map<String, Scheme> schemes = new HashMap<>();
        for (SerializationFormat f : SerializationFormat.values()) {
            for (SessionProtocol p : SessionProtocol.values()) {
                final String ftxt = f.uriText();
                final String ptxt = p.uriText();

                assert ftxt.equals(ftxt.toLowerCase(Locale.US));
                assert ptxt.equals(ptxt.toLowerCase(Locale.US));

                final Scheme scheme = new Scheme(f, p);
                schemes.put(ftxt + '+' + ptxt, scheme);
                schemes.put(ptxt + '+' + ftxt, scheme);
            }
        }

        SCHEMES = Collections.unmodifiableMap(schemes);
    }

    /**
     * Parses the specified {@link String} into a {@link Scheme}. This method will return the same
     * {@link Scheme} instance for equal values of {@code scheme}.
     *
     * @return {@link Optional#empty()} if the specified {@link String} could not be parsed or
     *         there is no such {@link Scheme} available
     */
    public static Optional<Scheme> tryParse(String scheme) {
        if (scheme == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(SCHEMES.get(scheme.toLowerCase(Locale.US)));
    }

    /**
     * Parses the specified {@link String} into a {@link Scheme}. This method will return the same
     * {@link Scheme} instance for equal values of {@code scheme}.
     *
     * @throws IllegalArgumentException if the specified {@link String} could not be parsed or
     *                                  there is no such {@link Scheme} available
     */
    public static Scheme parse(String scheme) {
        final Scheme res = SCHEMES.get(requireNonNull(scheme, "scheme").toLowerCase(Locale.US));
        if (res == null) {
            throw new IllegalArgumentException("scheme: " + scheme);
        }

        return res;
    }

    /**
     * Returns the {@link Scheme} of the specified {@link SerializationFormat} and {@link SessionProtocol}.
     * This method returns the same {@link Scheme} instance for the same combination of
     * {@link SerializationFormat} and {@link SessionProtocol}.
     */
    public static Scheme of(SerializationFormat serializationFormat, SessionProtocol sessionProtocol) {
        return SCHEMES.get(requireNonNull(serializationFormat, "serializationFormat").uriText() + '+' +
                           requireNonNull(sessionProtocol, "sessionProtocol").uriText());
    }

    private final SerializationFormat serializationFormat;
    private final SessionProtocol sessionProtocol;
    private final String uriText;

    private Scheme(SerializationFormat serializationFormat, SessionProtocol sessionProtocol) {
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        uriText = serializationFormat().uriText() + '+' + sessionProtocol().uriText();
    }

    /**
     * Returns the {@link SerializationFormat}.
     */
    public SerializationFormat serializationFormat() {
        return serializationFormat;
    }

    /**
     * Returns the {@link SessionProtocol}.
     */
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the textual representation ({@code "serializationFormat+sessionProtocol"}).
     */
    public String uriText() {
        return uriText;
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
    public int compareTo(Scheme o) {
        return uriText().compareTo(o.uriText());
    }

    @Override
    public String toString() {
        return uriText();
    }
}
