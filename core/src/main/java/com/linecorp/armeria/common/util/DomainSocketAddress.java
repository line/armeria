/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.net.UrlEscapers;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * An {@link InetSocketAddress} that refers to the {@link Path} of a Unix domain socket.
 * This class extends {@link InetSocketAddress} to ensure the backward compatibility with existing
 * Armeria API that uses {@link InetSocketAddress} in its API. This address will have the following properties:
 * <ul>
 *   <li>hostname and hostString - {@code "unix:<path>"}, e.g. {@code "unix:/var/run/server.sock"}</li>
 *   <li>address - an IPv6 address that falls into <a href="https://datatracker.ietf.org/doc/rfc6666/">IPv6
 *       Discard Prefix</a></li>
 * </ul>
 */
@UnstableApi
public final class DomainSocketAddress extends InetSocketAddress {

    private static final long serialVersionUID = 41779966264274119L;

    // Use an IPv6 address that falls into RFC 6666 (IPv6 Discard Prefix),
    // which will never reach anywhere. See https://datatracker.ietf.org/doc/rfc6666/
    private static final byte[] IPV6_DISCARD_ADDR = {
            1, 0, 0, 0, 0, 0, 0, 0, // 0100::/64
            'A', 'r', 'm', 'e', 'r', 'i', 'a', '!'
    };

    /**
     * Returns a newly created {@link DomainSocketAddress} with the specified {@link Path} to the Unix domain
     * socket.
     */
    public static DomainSocketAddress of(Path path) {
        return new DomainSocketAddress(requireNonNull(path, "path"));
    }

    /**
     * Returns a newly created {@link DomainSocketAddress} with the {@link Path} to the Unix domain socket
     * that the specified Netty address refers to.
     */
    public static DomainSocketAddress of(io.netty.channel.unix.DomainSocketAddress nettyAddr) {
        return new DomainSocketAddress(Paths.get(requireNonNull(nettyAddr, "nettyAddr").path()));
    }

    /**
     * Returns whether the specified {@link InetAddress} matches the special IPv6 address of
     * {@link DomainSocketAddress}. For example:
     * <pre>{@code
     * DomainSocketAddress sockAddr = DomainSocketAddress.of("/var/run/server.sock");
     * InetAddress inetAddr = sockAddr.getAddress();
     * assert isDomainSocketAddress(inetAddr);
     * }</pre>
     */
    public static boolean isDomainSocketAddress(InetAddress addr) {
        requireNonNull(addr, "addr");
        return Arrays.equals(addr.getAddress(), IPV6_DISCARD_ADDR);
    }

    private final Path path;
    @Nullable
    private String authority;
    @Nullable
    private Endpoint endpoint;
    @Nullable
    @SuppressWarnings("NullableOnContainingClass") // ErrorProne false positive
    private io.netty.channel.unix.DomainSocketAddress nettyAddress;

    private DomainSocketAddress(Path path) {
        super(toInetAddress(path), 1);
        this.path = path;
    }

    private static InetAddress toInetAddress(Path path) {
        try {
            return InetAddress.getByAddress("unix:" + path, IPV6_DISCARD_ADDR);
        } catch (UnknownHostException e) {
            throw new Error(e);
        }
    }

    /**
     * Returns the {@link Path} to the Unix domain socket.
     */
    public Path path() {
        return path;
    }

    /**
     * Returns the authority (host) form of this address which can be used as a part of {@link URI}.
     */
    public String authority() {
        final String authority = this.authority;
        if (authority != null) {
            return authority;
        }

        final String newAuthority = escape("unix:" + path);
        this.authority = newAuthority;
        return newAuthority;
    }

    /**
     * Converts this address to a Netty {@link io.netty.channel.unix.DomainSocketAddress}.
     *
     * @return the converted Netty address
     */
    public io.netty.channel.unix.DomainSocketAddress asNettyAddress() {
        final io.netty.channel.unix.DomainSocketAddress nettyAddress = this.nettyAddress;
        if (nettyAddress != null) {
            return nettyAddress;
        }

        final io.netty.channel.unix.DomainSocketAddress newNettyAddress =
                new io.netty.channel.unix.DomainSocketAddress(path.toFile());
        this.nettyAddress = newNettyAddress;
        return newNettyAddress;
    }

    /**
     * Converts this address to an {@link Endpoint}.
     */
    public Endpoint asEndpoint() {
        final Endpoint endpoint = this.endpoint;
        if (endpoint != null) {
            return endpoint;
        }

        final Endpoint newEndpoint = Endpoint.of(authority());
        this.endpoint = newEndpoint;
        return newEndpoint;
    }

    /**
     * Returns a string representation of this address, such as {@code "unix:/path/to/sock"}.
     */
    @Override
    public String toString() {
        return path.toString();
    }

    private static final Pattern AT_OR_COLON = Pattern.compile("[@:]");

    @VisibleForTesting
    static String escape(String authority) {
        final String escaped = UrlEscapers.urlPathSegmentEscaper().escape(authority);
        // We need to escape `@` and `:` as well, so that the authority contains neither userinfo nor port.
        final Matcher matcher = AT_OR_COLON.matcher(escaped);
        try (TemporaryThreadLocals tmp = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tmp.stringBuilder();
            for (int i = 0; i < escaped.length();) {
                if (!matcher.find(i)) {
                    buf.append(escaped, i, escaped.length());
                    break;
                }
                final int pos = matcher.start();
                buf.append(escaped, i, pos);
                buf.append(escaped.charAt(pos) == '@' ? "%40" : "%3A");
                i = pos + 1;
            }
            return buf.toString();
        }
    }
}
