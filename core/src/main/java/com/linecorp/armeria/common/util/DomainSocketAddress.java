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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.DomainSocketUtil;

/**
 * An {@link InetSocketAddress} that refers to a Unix domain socket path.
 * This class extends {@link InetSocketAddress} to ensure the backward compatibility with existing
 * Armeria API that uses {@link InetSocketAddress} in its API. This address will have the following properties:
 * <ul>
 *   <li>hostname and hostString - {@code "unix:<path>"}, e.g. {@code "unix:/var/run/server.sock"}</li>
 *   <li>address - an IPv6 address that falls into <a href="https://datatracker.ietf.org/doc/rfc6666/">IPv6
 *       Discard Prefix</a></li>
 * </ul>
 *
 * <h2>Pitfalls</h2>
 * <p>Comparing two {@link DomainSocketAddress}es using {@link #equals(Object)} will always return {@code true}
 * because there's no way to override {@link InetSocketAddress#equals(Object)}, which compares only the IP
 * addresses for resolved addresses. You should never use {@link DomainSocketAddress} as a key of a {@link Map},
 * or as an element of a {@link Set}; consider using {@link Endpoint} instead.</p>
 */
@UnstableApi
public final class DomainSocketAddress extends InetSocketAddress {

    private static final long serialVersionUID = 41779966264274119L;

    /**
     * Returns a newly created {@link DomainSocketAddress} with the specified {@link Path} to the Unix domain
     * socket.
     */
    public static DomainSocketAddress of(Path path) {
        return of(requireNonNull(path, "path").toString());
    }

    /**
     * Returns a newly created {@link DomainSocketAddress} with the specified {@code path} to the Unix domain
     * socket.
     */
    public static DomainSocketAddress of(String path) {
        requireNonNull(path, "path");
        checkArgument(!path.isEmpty(), "path must not be empty.");
        return new DomainSocketAddress(path);
    }

    /**
     * Returns a newly created {@link DomainSocketAddress} with the Unix domain socket path of the specified
     * Netty address.
     */
    public static DomainSocketAddress of(io.netty.channel.unix.DomainSocketAddress nettyAddr) {
        final String path = requireNonNull(nettyAddr, "nettyAddr").path();
        checkArgument(!path.isEmpty(), "nettyAddr.path must not be empty.");
        return new DomainSocketAddress(path);
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
        return DomainSocketUtil.isDomainSocketAddress(addr);
    }

    private final String path;
    @Nullable
    private String authority;
    @Nullable
    private Endpoint endpoint;
    @Nullable
    @SuppressWarnings("NullableOnContainingClass") // ErrorProne false positive
    private io.netty.channel.unix.DomainSocketAddress nettyAddress;

    private DomainSocketAddress(String path) {
        super(DomainSocketUtil.toInetAddress(requireNonNull(path, "path")),
              DomainSocketUtil.DOMAIN_SOCKET_PORT);
        this.path = path;
    }

    /**
     * Returns the path to the Unix domain socket.
     */
    public String path() {
        return path;
    }

    /**
     * Returns {@code true} if this address is in the abstract namespace.
     */
    public boolean isAbstract() {
        return path.charAt(0) == 0;
    }

    /**
     * Returns the authority (host) form of this address which can be used as a part of {@link URI}.
     */
    public String authority() {
        final String authority = this.authority;
        if (authority != null) {
            return authority;
        }

        final String newAuthority = DomainSocketUtil.toAuthority(path);
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
                new io.netty.channel.unix.DomainSocketAddress(path);
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
     * Returns a string representation of this address, such as {@code "/path/to/sock"} or {@code "@sock"}
     * (abstract namespace).
     */
    @Override
    public String toString() {
        if (isAbstract()) {
            return path.replace('\0', '@');
        } else {
            return path;
        }
    }
}
