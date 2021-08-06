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
package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A source which is used to get a client address. Currently there are two sources which are available
 * to provide a client address:
 * <ul>
 *     <li>an HTTP header, such as {@code Forwarded} and {@code X-Forwarded-For} header</li>
 *     <li>the source address specified in a
 *     <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">PROXY protocol</a> message</li>
 * </ul>
 */
public final class ClientAddressSource {

    private static final ClientAddressSource PROXY_PROTOCOL =
            new ClientAddressSource(HttpHeaderNames.of("PROXY_PROTOCOL"));

    /**
     * A default list of {@link ClientAddressSource}s.
     */
    static final List<ClientAddressSource> DEFAULT_SOURCES =
            ImmutableList.of(ofHeader(HttpHeaderNames.FORWARDED),
                             ofHeader(HttpHeaderNames.X_FORWARDED_FOR),
                             ofProxyProtocol());

    /**
     * Returns a {@link ClientAddressSource} which indicates the value of the specified {@code header}
     * in a request will be used to determine a client address.
     */
    public static ClientAddressSource ofHeader(CharSequence header) {
        checkArgument(requireNonNull(header, "header").length() > 0, "empty header");
        return new ClientAddressSource(HttpHeaderNames.of(header));
    }

    /**
     * Returns the {@link ClientAddressSource} which indicates the source address specified in
     * a <a href="https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt">PROXY protocol</a> message
     * will be used to determine a client address.
     */
    public static ClientAddressSource ofProxyProtocol() {
        return PROXY_PROTOCOL;
    }

    private final AsciiString header;

    private ClientAddressSource(AsciiString header) {
        this.header = header;
    }

    /**
     * Returns the name of an HTTP header. The value of the header in a request will be used to determine
     * a client address.
     */
    AsciiString header() {
        return header;
    }

    /**
     * Returns {@code true} if the specified {@code source} is for a PROXY protocol.
     */
    boolean isProxyProtocol() {
        return equals(PROXY_PROTOCOL);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientAddressSource)) {
            return false;
        }
        final ClientAddressSource that = (ClientAddressSource) o;
        return header.equals(that.header);
    }

    @Override
    public int hashCode() {
        return header.hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("header", header)
                          .toString();
    }
}
