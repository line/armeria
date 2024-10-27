/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.proxy;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.client.proxy.DirectProxyConfig.DIRECT_PROXY_CONFIG;
import static java.util.Objects.requireNonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Base configuration for proxy settings used by {@link ClientFactory}.
 */
public abstract class ProxyConfig {

    private static final Logger logger = LoggerFactory.getLogger(ProxyConfig.class);

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     * @param refreshInterval the DNS refresh time
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, null, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, String username) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, requireNonNull(username, "username"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS4 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param refreshInterval the DNS refresh time
     */
    public static Socks4ProxyConfig socks4(InetSocketAddress proxyAddress, String username,
                                           long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks4ProxyConfig(proxyAddress, requireNonNull(username, "username"), refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static Socks5ProxyConfig socks5(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, null, null);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     * @param refreshInterval the DNS refresh time
     */
    public static Socks5ProxyConfig socks5(InetSocketAddress proxyAddress, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, null, null, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     */
    public static Socks5ProxyConfig socks5(
            InetSocketAddress proxyAddress, String username, String password) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, requireNonNull(username, "username"),
                                     requireNonNull(password, "password"));
    }

    /**
     * Creates a {@code ProxyConfig} configuration for SOCKS5 protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param refreshInterval the DNS refresh time
     */
    public static Socks5ProxyConfig socks5(
            InetSocketAddress proxyAddress, String username, String password, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new Socks5ProxyConfig(proxyAddress, requireNonNull(username, "username"),
                                     requireNonNull(password, "password"),
                                     refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, HttpHeaders.of(), false);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param refreshInterval the DNS refresh time
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, HttpHeaders.of(), false, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param useTls whether to use TLS to connect to the proxy
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, boolean useTls) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, HttpHeaders.of(), useTls);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param useTls whether to use TLS to connect to the proxy
     * @param refreshInterval the DNS refresh time
     */
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, boolean useTls,
                                             long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, HttpHeaders.of(), useTls, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param useTls whether to use TLS to connect to the proxy
     */
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, String username, String password, boolean useTls) {
        return connect(proxyAddress, username, password, HttpHeaders.of(), useTls);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param useTls whether to use TLS to connect to the proxy
     * @param refreshInterval the DNS refresh time
     */
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, String username, String password, boolean useTls,
            long refreshInterval) {
        return connect(proxyAddress, username, password, HttpHeaders.of(), useTls, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param headers the {@link HttpHeaders} to send to the proxy
     * @param useTls whether to use TLS to connect to the proxy
     */
    @UnstableApi
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, HttpHeaders headers, boolean useTls) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, headers, useTls);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param headers the {@link HttpHeaders} to send to the proxy
     * @param useTls whether to use TLS to connect to the proxy
     * @param refreshInterval the DNS refresh time
     */
    @UnstableApi
    public static ConnectProxyConfig connect(
            InetSocketAddress proxyAddress, HttpHeaders headers, boolean useTls, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new ConnectProxyConfig(proxyAddress, null, null, headers, useTls, refreshInterval);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param headers the {@link HttpHeaders} to send to the proxy
     * @param useTls whether to use TLS to connect to the proxy
     */
    @UnstableApi
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, String username, String password,
                                             HttpHeaders headers, boolean useTls) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        requireNonNull(username, "username");
        requireNonNull(password, "password");
        requireNonNull(headers, "headers");
        return new ConnectProxyConfig(proxyAddress, username, password, headers, useTls);
    }

    /**
     * Creates a {@code ProxyConfig} configuration for CONNECT protocol.
     *
     * @param proxyAddress the proxy address
     * @param username the username
     * @param password the password
     * @param headers the {@link HttpHeaders} to send to the proxy
     * @param useTls whether to use TLS to connect to the proxy
     * @param refreshInterval the DNS refresh time
     */
    @UnstableApi
    public static ConnectProxyConfig connect(InetSocketAddress proxyAddress, String username, String password,
                                             HttpHeaders headers, boolean useTls, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        requireNonNull(username, "username");
        requireNonNull(password, "password");
        requireNonNull(headers, "headers");
        return new ConnectProxyConfig(proxyAddress, username, password, headers, useTls, refreshInterval);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol.
     *
     * @param proxyAddress the proxy address
     * @param sourceAddress the source address
     */
    public static HAProxyConfig haproxy(
            InetSocketAddress proxyAddress, InetSocketAddress sourceAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        requireNonNull(sourceAddress, "sourceAddress");
        checkArgument(!sourceAddress.isUnresolved(), "sourceAddress must be resolved");
        return new HAProxyConfig(proxyAddress, sourceAddress);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol.
     *
     * @param proxyAddress the proxy address
     * @param sourceAddress the source address
     * @param refreshInterval the DNS refresh time
     */
    public static HAProxyConfig haproxy(
            InetSocketAddress proxyAddress, InetSocketAddress sourceAddress, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        requireNonNull(sourceAddress, "sourceAddress");
        checkArgument(!sourceAddress.isUnresolved(), "sourceAddress must be resolved");
        return new HAProxyConfig(proxyAddress, sourceAddress, refreshInterval);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol. The {@code sourceAddress} will
     * be inferred from either the {@link ServiceRequestContext} or the local connection address.
     *
     * @param proxyAddress the proxy address
     */
    public static ProxyConfig haproxy(InetSocketAddress proxyAddress) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new HAProxyConfig(proxyAddress);
    }

    /**
     * Creates a {@link ProxyConfig} configuration for HAProxy protocol. The {@code sourceAddress} will
     * be inferred from either the {@link ServiceRequestContext} or the local connection address.
     *
     * @param proxyAddress the proxy address
     * @param refreshInterval the DNS refresh time
     */
    public static ProxyConfig haproxy(InetSocketAddress proxyAddress, long refreshInterval) {
        requireNonNull(proxyAddress, "proxyAddress");
        checkArgument(!proxyAddress.isUnresolved(), "proxyAddress must be resolved");
        return new HAProxyConfig(proxyAddress, refreshInterval);
    }

    /**
     * Returns a {@code ProxyConfig} which signifies that a proxy is absent.
     */
    public static ProxyConfig direct() {
        return DIRECT_PROXY_CONFIG;
    }

    ProxyConfig() {
    }

    /**
     * Returns the proxy type.
     */
    public abstract ProxyType proxyType();

    /**
     * Returns the proxy address which is {@code null} only for {@link ProxyType#DIRECT}.
     */
    @Nullable
    public abstract InetSocketAddress proxyAddress();

    @Nullable
    static String maskPassword(@Nullable String username, @Nullable String password) {
        return username != null ? "****" : null;
    }

    /**
     * Reserves a task to periodically update DNS with a given scheduler.
     *
     * @param updateCallback The callback to update InetSocketAddress
     * @param hostname The hostname
     * @param port The port number
     * @param refreshInterval The refresh Interval
     * @param scheduler The scheduler
     */
    protected static void reserveDNSUpdate(BiConsumer<InetSocketAddress, Long> updateCallback,
                                  String hostname,
                                  int port,
                                  long refreshInterval,
                                  ScheduledExecutorService scheduler) {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                final String ipAddress = InetAddress.getByName(hostname)
                                                    .getHostAddress();
                final InetSocketAddress newInetSocketAddress = new InetSocketAddress(ipAddress, port);
                final long lastUpdateTime = System.currentTimeMillis();
                updateCallback.accept(newInetSocketAddress,
                                      lastUpdateTime);
            } catch (UnknownHostException e) {
                logger.warn("Failed to refresh {}'s ip address. " +
                            "Use the previous inet address instead.",
                            hostname);
            }
        }, 0, refreshInterval, TimeUnit.MILLISECONDS);
    }
}
