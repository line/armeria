/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AttributesGetters;
import com.linecorp.armeria.common.ConcurrentAttributes;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.ChannelUtil;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A read-only context representing a newly accepted connection. Provides connection-level
 * properties parsed from the TLS ClientHello (for TLS connections) and attribute storage
 * for passing per-connection state through the server pipeline.
 *
 * <p>A {@link ConnectionContext} is created by the server pipeline for each connection and
 * is accessible from {@link ServiceRequestContext#connectionContext()} so that service
 * decorators can access connection-level state at request time.
 */
@UnstableApi
public final class ConnectionContext {

    static final AttributeKey<ConnectionContext> ATTR =
            AttributeKey.valueOf(ConnectionContext.class, "CONNECTION_CONTEXT");

    private static final InetSocketAddress UNKNOWN_ADDR;

    static {
        InetAddress unknownAddr;
        try {
            unknownAddr = InetAddress.getByAddress("<unknown>", new byte[] { 0, 0, 0, 0 });
        } catch (Exception e1) {
            try {
                unknownAddr = InetAddress.getByAddress(new byte[] { 0, 0, 0, 0 });
            } catch (Exception e2) {
                final Error err = new Error(e2);
                err.addSuppressed(e1);
                throw err;
            }
        }
        UNKNOWN_ADDR = new InetSocketAddress(unknownAddr, 1);
    }

    private final SessionProtocol sessionProtocol;
    @Nullable
    private final String sniHostname;
    private final List<String> alpnProtocols;
    @Nullable
    private final ProxiedAddresses proxiedAddresses;
    private final InetSocketAddress remoteAddress;
    private final InetSocketAddress localAddress;
    private final ConcurrentAttributes attrs = ConcurrentAttributes.of();

    @Nullable
    static ConnectionContext get(Channel channel) {
        return channel.attr(ATTR).get();
    }

    ConnectionContext(SessionProtocol sessionProtocol, @Nullable String sniHostname,
                     List<String> alpnProtocols,
                     @Nullable ProxiedAddresses proxiedAddresses, Channel channel) {
        this.sessionProtocol = sessionProtocol;
        this.sniHostname = sniHostname;
        this.alpnProtocols = ImmutableList.copyOf(alpnProtocols);
        this.proxiedAddresses = proxiedAddresses;
        remoteAddress = firstNonNull(ChannelUtil.remoteAddress(channel), UNKNOWN_ADDR);
        localAddress = firstNonNull(ChannelUtil.localAddress(channel), UNKNOWN_ADDR);
    }

    /**
     * Returns the {@link SessionProtocol} of this connection.
     * Either {@link SessionProtocol#HTTP} or {@link SessionProtocol#HTTPS} will be returned.
     */
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the SNI hostname from the TLS ClientHello, or {@code null} if
     * the connection is not TLS or no SNI was provided.
     */
    @Nullable
    public String sniHostname() {
        return sniHostname;
    }

    /**
     * Returns the ALPN protocols offered in the TLS ClientHello, or an empty list
     * if the connection is not TLS or no ALPN extension was present.
     */
    public List<String> alpnProtocols() {
        return alpnProtocols;
    }

    /**
     * Returns the proxied addresses of this connection from the PROXY protocol,
     * or {@code null} if the connection did not use the PROXY protocol.
     */
    @Nullable
    public ProxiedAddresses proxiedAddresses() {
        return proxiedAddresses;
    }

    /**
     * Returns the local address of this connection.
     */
    public InetSocketAddress localAddress() {
        return localAddress;
    }

    /**
     * Returns the remote address of this connection.
     */
    public InetSocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * Returns the value associated with the given {@link AttributeKey}, or {@code null} if not set.
     */
    @Nullable
    public <T> T attr(AttributeKey<T> key) {
        return attrs.attr(requireNonNull(key, "key"));
    }

    /**
     * Sets the value associated with the given {@link AttributeKey}.
     */
    public <T> void setAttr(AttributeKey<T> key, @Nullable T value) {
        attrs.set(requireNonNull(key, "key"), value);
    }

    /**
     * Returns the {@link AttributesGetters} that contains the attributes of this connection.
     */
    public AttributesGetters attrs() {
        return attrs;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("sessionProtocol", sessionProtocol)
                          .add("sniHostname", sniHostname)
                          .add("alpnProtocols", alpnProtocols)
                          .add("proxiedAddresses", proxiedAddresses)
                          .add("remoteAddress", remoteAddress)
                          .add("localAddress", localAddress)
                          .toString();
    }
}
