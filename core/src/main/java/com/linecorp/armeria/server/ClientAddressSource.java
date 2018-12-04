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

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

/**
 * A source which is used to get a client address. Currently there are two sources which are available
 * to provide a client address:
 * <ul>
 *     <li>an HTTP header, such as {@code Forwarded} and {@code X-Forwarded-For} header</li>
 *     <li>the source address of a PROXY protocol header</li>
 * </ul>
 */
public final class ClientAddressSource {

    private static final ClientAddressSource PROXY_PROTOCOL =
            new ClientAddressSource(AsciiString.of("PROXY_PROTOCOL"));

    /**
     * A default list of {@link ClientAddressSource}s.
     */
    static final List<ClientAddressSource> DEFAULT_SOURCES =
            ImmutableList.of(ofHeader(HttpHeaderNames.FORWARDED),
                             ofHeader(HttpHeaderNames.X_FORWARDED_FOR),
                             ofProxyProtocol());

    /**
     * Returns a {@link ClientAddressSource} of the specified {@code header} which will be used to determine
     * a client address from a request.
     */
    public static ClientAddressSource ofHeader(AsciiString header) {
        checkArgument(header != null && !header.isEmpty(), "empty header");
        return new ClientAddressSource(header);
    }

    /**
     * Returns a {@link ClientAddressSource} of the specified {@code header} which will be used to determine
     * a client address from a request.
     */
    public static ClientAddressSource ofHeader(String header) {
        checkArgument(!Strings.isNullOrEmpty(header), "empty header");
        return new ClientAddressSource(AsciiString.cached(header));
    }

    /**
     * Returns a {@link ClientAddressSource} of a PROXY protocol which indicates a {@link ProxiedAddresses}
     * will be used to determine a client address if a request has came via PROXY protocol.
     */
    public static ClientAddressSource ofProxyProtocol() {
        return PROXY_PROTOCOL;
    }

    /**
     * Returns {@code true} if the specified {@code source} is for a PROXY protocol.
     */
    static boolean isProxyProtocol(ClientAddressSource source) {
        return source == PROXY_PROTOCOL;
    }

    private final AsciiString header;

    private ClientAddressSource(AsciiString header) {
        this.header = header;
    }

    AsciiString header() {
        return header;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("header", header)
                          .toString();
    }
}
