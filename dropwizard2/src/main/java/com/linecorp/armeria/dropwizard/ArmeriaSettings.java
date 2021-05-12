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
/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.linecorp.armeria.dropwizard;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.VirtualHostBuilder;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslProvider;

/**
 * Settings for armeria servers, e.g.,
 * <pre>{@code
 * gracefulShutdownQuietPeriodMillis: 5000
 * gracefulShutdownTimeoutMillis: 40000
 * maxRequestLength: 10485760
 * maxNumConnections: 5000
 * dateHeaderEnabled: false
 * serverHeaderEnabled: true
 * verboseResponses: false
 * defaultHostname: "host.name.com"
 * ports:
 *   - port: 8080
 *     protocol: HTTP
 *   - ip: 127.0.0.1
 *     port: 8081
 *     protocol: HTTP
 *   - port: 8443
 *     protocols:
 *       - HTTPS
 *       - PROXY
 * ssl:
 *   keyAlias: "host.name.com"
 *   keyStore: "keystore.jks"
 *   keyStorePassword: "changeme"
 *   trustStore: "truststore.jks"
 *   trustStorePassword: "changeme"
 * compression:
 *   enabled: true
 *   mimeTypes:
 *     - text/*
 *     - application/json
 *   excludedUserAgents:
 *     - some-user-agent
 *     - another-user-agent
 *   minResponseSize: 1KB
 * proxy:
 *   maxTlvSize: 65319
 * http1:
 *   maxChunkSize: 4096
 *   maxInitialLineLength: 4096
 * http2:
 *   initialConnectionWindowSize: 1MB
 *   initialStreamWindowSize: 1MB
 *   maxFrameSize: 16384
 *   maxHeaderListSize: 8192
 * accessLog:
 *   type: custom
 *   format: "Your own log format"
 * ...
 *
 * }</pre>
 * TODO(ikhoon): Merge this DropWizard ArmeriaSettings with c.l.a.spring.ArmeriaSettings
 *               to provide common API to configure Server from JSON and YAML.
 */
class ArmeriaSettings {

    /**
     * The ports to listen on for requests. If not specified, will listen on
     * port 8080 for HTTP (not SSL).
     */
    private List<Port> ports = new ArrayList<>();

    /**
     * The number of milliseconds to wait after the last processed request to
     * be considered safe for shutdown. This should be set at least as long as
     * the slowest possible request to guarantee graceful shutdown. If {@code -1},
     * graceful shutdown will be disabled.
     */
    private long gracefulShutdownQuietPeriodMillis = 5000;

    /**
     * The number of milliseconds to wait after going unhealthy before forcing
     * the server to shutdown regardless of if it is still processing requests.
     * This should be set as long as the maximum time for the load balancer to
     * turn off requests to the server. If {@code -1}, graceful shutdown will
     * be disabled.
     */
    private long gracefulShutdownTimeoutMillis = 40000;

    /**
     * The maximum allowed length of the content decoded at the session layer.
     */
    @Nullable
    private Integer maxRequestLength;

    /**
     * The maximum allowed number of open connections.
     */
    @Nullable
    private Integer maxNumConnections;

    /**
     * If enabled, the {@code "Date"} header is included in the response header.
     */
    private boolean dateHeaderEnabled = true;

    /**
     * If enabled, the {@code "Server"} header is included in the response header.
     */
    private boolean serverHeaderEnabled;

    /**
     * {@code true} if the verbose response mode is enabled.
     */
    private boolean verboseResponses;

    /**
     * The default hostname of the default {@link VirtualHostBuilder}.
     */
    @Nullable
    private String defaultHostname;

    /**
     * SSL configuration that the {@link Server} uses.
     */
    @Nullable
    private Ssl ssl;

    /**
     * Compression configuration that the {@link Server} uses.
     */
    @Nullable
    private Compression compression;

    /**
     * HTTP/1 configuration that the {@link Server} uses.
     */
    @Nullable
    private Http1 http1;

    /**
     * HTTP/2 configuration that the {@link Server} uses.
     */
    @Nullable
    private Http2 http2;

    /**
     * PROXY configuration that the {@link Server} uses.
     */
    @Nullable
    private Proxy proxy;

    /**
     * Access Log configuration that the {@link Server} uses.
     */
    @Nullable
    private AccessLog accessLog;

    /**
     * Returns the {@link Port}s of the {@link Server}.
     */
    List<Port> getPorts() {
        return ports;
    }

    /**
     * Sets the {@link Port}s of the {@link Server}.
     */
    void setPorts(List<Port> ports) {
        this.ports = ports;
    }

    long getGracefulShutdownQuietPeriodMillis() {
        return gracefulShutdownQuietPeriodMillis;
    }

    void setGracefulShutdownQuietPeriodMillis(long gracefulShutdownQuietPeriodMillis) {
        this.gracefulShutdownQuietPeriodMillis = gracefulShutdownQuietPeriodMillis;
    }

    long getGracefulShutdownTimeoutMillis() {
        return gracefulShutdownTimeoutMillis;
    }

    void setGracefulShutdownTimeoutMillis(long gracefulShutdownTimeoutMillis) {
        this.gracefulShutdownTimeoutMillis = gracefulShutdownTimeoutMillis;
    }

    @Nullable
    Integer getMaxRequestLength() {
        return maxRequestLength;
    }

    void setMaxRequestLength(@Nullable Integer maxRequestLength) {
        this.maxRequestLength = maxRequestLength;
    }

    @Nullable
    Integer getMaxNumConnections() {
        return maxNumConnections;
    }

    void setMaxNumConnections(Integer maxNumConnections) {
        this.maxNumConnections = maxNumConnections;
    }

    boolean isDateHeaderEnabled() {
        return dateHeaderEnabled;
    }

    void setDateHeaderEnabled(boolean dateHeaderEnabled) {
        this.dateHeaderEnabled = dateHeaderEnabled;
    }

    boolean isServerHeaderEnabled() {
        return serverHeaderEnabled;
    }

    void setServerHeaderEnabled(boolean serverHeaderEnabled) {
        this.serverHeaderEnabled = serverHeaderEnabled;
    }

    boolean isVerboseResponses() {
        return verboseResponses;
    }

    void setVerboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
    }

    @Nullable
    String getDefaultHostname() {
        return defaultHostname;
    }

    void setDefaultHostname(@Nullable String defaultHostname) {
        this.defaultHostname = defaultHostname;
    }

    @Nullable
    Ssl getSsl() {
        return ssl;
    }

    void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }

    @Nullable
    Compression getCompression() {
        return compression;
    }

    void setCompression(Compression compression) {
        this.compression = compression;
    }

    @Nullable
    Http1 getHttp1() {
        return http1;
    }

    void setHttp1(@Nullable Http1 http1) {
        this.http1 = http1;
    }

    @Nullable
    Http2 getHttp2() {
        return http2;
    }

    void setHttp2(@Nullable Http2 http2) {
        this.http2 = http2;
    }

    @Nullable
    Proxy getProxy() {
        return proxy;
    }

    void setProxy(@Nullable Proxy proxy) {
        this.proxy = proxy;
    }

    @Nullable
    AccessLog getAccessLog() {
        return accessLog;
    }

    void setAccessLog(@Nullable AccessLog accessLog) {
        this.accessLog = accessLog;
    }

    /**
     * Port and protocol settings.
     */
    static class Port {
        /**
         * IP address to bind to. If not set, will bind to all addresses, e.g. {@code 0.0.0.0}.
         */
        @Nullable
        private String ip;

        /**
         * Network interface to bind to. If not set, will bind to the first detected network interface.
         */
        @Nullable
        private String iface;

        /**
         * Port that {@link Server} binds to.
         */
        private int port;

        /**
         * Protocol that will be used in this ip/iface and port.
         */
        @Nullable
        private List<SessionProtocol> protocols;

        @Nullable
        String getIp() {
            return ip;
        }

        Port setIp(String ip) {
            this.ip = ip;
            return this;
        }

        @Nullable
        String getIface() {
            return iface;
        }

        Port setIface(String iface) {
            this.iface = iface;
            return this;
        }

        int getPort() {
            return port;
        }

        Port setPort(int port) {
            this.port = port;
            return this;
        }

        @Nullable
        List<SessionProtocol> getProtocols() {
            return protocols;
        }

        Port setProtocols(List<SessionProtocol> protocols) {
            this.protocols = ImmutableList.copyOf(protocols);
            return this;
        }

        Port setProtocol(SessionProtocol protocol) {
            protocols = ImmutableList.of(protocol);
            return this;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Port)) {
                return false;
            }
            final Port that = (Port) o;
            return port == that.port &&
                   Objects.equals(ip, that.ip) &&
                   Objects.equals(iface, that.iface) &&
                   Objects.equals(protocols, that.protocols);
        }

        @Override
        public int hashCode() {
            return Objects.hash(ip, iface, port, protocols);
        }
    }

    /**
     * Simple server-independent abstraction for SSL configuration.
     *
     * @author Andy Wilkinson
     * @author Vladimir Tsanev
     * @author Stephane Nicoll
     */
    static class Ssl {
        private boolean enabled = true;

        @Nullable
        private SslProvider provider;

        @Nullable
        private ClientAuth clientAuth;

        @Nullable
        private List<String> ciphers;

        @Nullable
        private List<String> enabledProtocols;

        @Nullable
        private String keyAlias;

        @Nullable
        private String keyPassword;

        @Nullable
        private String keyStore;

        @Nullable
        private String keyStorePassword;

        @Nullable
        private String keyStoreType;

        @Nullable
        private String keyStoreProvider;

        @Nullable
        private String trustStore;

        @Nullable
        private String trustStorePassword;

        @Nullable
        private String trustStoreType;

        @Nullable
        private String trustStoreProvider;

        /**
         * Returns whether to enable SSL support.
         * @return whether to enable SSL support
         */
        boolean isEnabled() {
            return enabled;
        }

        /**
         * Enables (or disables) SSL support.
         */
        Ssl setEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * Returns Netty SSL Provider.
         * @return Netty SSL Provider
         */
        @Nullable
        SslProvider getProvider() {
            return provider;
        }

        /**
         * Sets Netty SSL Provider namely JDK or OPENSSL  {@link SslProvider}.
         * @param provider Netty SSL Provider
         */
        void setProvider(SslProvider provider) {
            this.provider = provider;
        }

        /**
         * Returns whether client authentication is not wanted ("none"), wanted ("want") or
         * needed ("need"). Requires a trust store.
         * @return the {@link ClientAuth} to use
         */
        @Nullable
        ClientAuth getClientAuth() {
            return clientAuth;
        }

        /**
         * Sets whether the client authentication is not none ({@link ClientAuth#NONE}), optional
         * ({@link ClientAuth#OPTIONAL}) or required ({@link ClientAuth#REQUIRE}).
         */
        Ssl setClientAuth(ClientAuth clientAuth) {
            this.clientAuth = clientAuth;
            return this;
        }

        /**
         * Returns the supported SSL ciphers.
         * @return the supported SSL ciphers
         */
        @Nullable
        List<String> getCiphers() {
            return ciphers;
        }

        /**
         * Sets the supported SSL ciphers.
         */
        Ssl setCiphers(List<String> ciphers) {
            this.ciphers = ciphers;
            return this;
        }

        /**
         * Returns the enabled SSL protocols.
         * @return the enabled SSL protocols.
         */
        @Nullable
        List<String> getEnabledProtocols() {
            return enabledProtocols;
        }

        /**
         * Sets the enabled SSL protocols.
         */
        Ssl setEnabledProtocols(List<String> enabledProtocols) {
            this.enabledProtocols = enabledProtocols;
            return this;
        }

        /**
         * Returns the alias that identifies the key in the key store.
         * @return the key alias
         */
        @Nullable
        String getKeyAlias() {
            return keyAlias;
        }

        /**
         * Sets the alias that identifies the key in the key store.
         */
        Ssl setKeyAlias(String keyAlias) {
            this.keyAlias = keyAlias;
            return this;
        }

        /**
         * Returns the password used to access the key in the key store.
         * @return the key password
         */
        @Nullable
        String getKeyPassword() {
            return keyPassword;
        }

        /**
         * Sets the password used to access the key in the key store.
         */
        Ssl setKeyPassword(String keyPassword) {
            this.keyPassword = keyPassword;
            return this;
        }

        /**
         * Returns the path to the key store that holds the SSL certificate (typically a jks
         * file).
         * @return the path to the key store
         */
        @Nullable
        String getKeyStore() {
            return keyStore;
        }

        /**
         * Sets the path to the key store that holds the SSL certificate (typically a jks file).
         */
        Ssl setKeyStore(String keyStore) {
            this.keyStore = keyStore;
            return this;
        }

        /**
         * Returns the password used to access the key store.
         * @return the key store password
         */
        @Nullable
        String getKeyStorePassword() {
            return keyStorePassword;
        }

        /**
         * Sets the password used to access the key store.
         */
        Ssl setKeyStorePassword(String keyStorePassword) {
            this.keyStorePassword = keyStorePassword;
            return this;
        }

        /**
         * Returns the type of the key store.
         * @return the key store type
         */
        @Nullable
        String getKeyStoreType() {
            return keyStoreType;
        }

        /**
         * Sets the type of the key store.
         */
        Ssl setKeyStoreType(String keyStoreType) {
            this.keyStoreType = keyStoreType;
            return this;
        }

        /**
         * Returns the provider for the key store.
         * @return the key store provider
         */
        @Nullable
        String getKeyStoreProvider() {
            return keyStoreProvider;
        }

        /**
         * Sets the provider for the key store.
         */
        Ssl setKeyStoreProvider(String keyStoreProvider) {
            this.keyStoreProvider = keyStoreProvider;
            return this;
        }

        /**
         * Returns the trust store that holds SSL certificates.
         * @return the trust store
         */
        @Nullable
        String getTrustStore() {
            return trustStore;
        }

        /**
         * Sets the trust store that holds SSL certificates.
         */
        Ssl setTrustStore(String trustStore) {
            this.trustStore = trustStore;
            return this;
        }

        /**
         * Returns the password used to access the trust store.
         * @return the trust store password
         */
        @Nullable
        String getTrustStorePassword() {
            return trustStorePassword;
        }

        /**
         * Sets the password used to access the trust store.
         */
        Ssl setTrustStorePassword(String trustStorePassword) {
            this.trustStorePassword = trustStorePassword;
            return this;
        }

        /**
         * Returns the type of the trust store.
         * @return the trust store type
         */
        @Nullable
        String getTrustStoreType() {
            return trustStoreType;
        }

        /**
         * Sets the type of the trust store.
         */
        Ssl setTrustStoreType(String trustStoreType) {
            this.trustStoreType = trustStoreType;
            return this;
        }

        /**
         * Returns the provider for the trust store.
         * @return the trust store provider
         */
        @Nullable
        String getTrustStoreProvider() {
            return trustStoreProvider;
        }

        /**
         * Sets the provider for the trust store.
         */
        Ssl setTrustStoreProvider(String trustStoreProvider) {
            this.trustStoreProvider = trustStoreProvider;
            return this;
        }
    }

    /**
     * Configurations for the HTTP content encoding.
     */
    static class Compression {
        /**
         * The default MIME Types of an HTTP response which are applicable for the HTTP content encoding.
         */
        private static final String[] DEFAULT_MIME_TYPES =
                Stream.of(MediaType.HTML_UTF_8, MediaType.XML_UTF_8, MediaType.PLAIN_TEXT_UTF_8,
                          MediaType.CSS_UTF_8, MediaType.TEXT_JAVASCRIPT_UTF_8,
                          MediaType.JAVASCRIPT_UTF_8, MediaType.APPLICATION_XML_UTF_8)
                      .map(MediaType::nameWithoutParameters)
                      .toArray(String[]::new);

        /**
         * Specifies whether the HTTP content encoding is enabled.
         */
        private boolean enabled;

        /**
         * The MIME Types of an HTTP response which are applicable for the HTTP content encoding.
         */
        private String[] mimeTypes = DEFAULT_MIME_TYPES;

        /**
         * The {@code "user-agent"} header values which are not applicable for the HTTP content encoding.
         */
        @Nullable
        private String[] excludedUserAgents;

        /**
         * The minimum bytes for encoding the content of an HTTP response.
         */
        @Nullable
        private String minResponseSize;

        boolean isEnabled() {
            return enabled;
        }

        void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        @Nullable
        String[] getMimeTypes() {
            return mimeTypes;
        }

        void setMimeTypes(String[] mimeTypes) {
            this.mimeTypes = mimeTypes;
        }

        @Nullable
        String[] getExcludedUserAgents() {
            return excludedUserAgents;
        }

        void setExcludedUserAgents(String[] excludedUserAgents) {
            this.excludedUserAgents = excludedUserAgents;
        }

        @Nullable
        String getMinResponseSize() {
            return minResponseSize;
        }

        void setMinResponseSize(String minResponseSize) {
            this.minResponseSize = minResponseSize;
        }
    }

    /**
     * Configurations for the PROXY.
     */
    static class Proxy {
        /**
         * the maximum size of additional data for PROXY protocol.
         */
        @Nullable
        private String maxTlvSize;

        @Nullable
        String getMaxTlvSize() {
            return maxTlvSize;
        }

        void setMaxTlvSize(String maxTlvSize) {
            this.maxTlvSize = maxTlvSize;
        }
    }

    /**
     * Configurations for the HTTP/1.
     */
    static class Http1 {
        /**
         * The maximum length of each chunk in an HTTP/1 response content.
         */
        @Nullable
        private String maxChunkSize;

        /**
         * The maximum length of an HTTP/1 response initial line.
         */
        @Nullable
        private Integer maxInitialLineLength;

        @Nullable
        String getMaxChunkSize() {
            return maxChunkSize;
        }

        void setMaxChunkSize(String maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
        }

        @Nullable
        Integer getMaxInitialLineLength() {
            return maxInitialLineLength;
        }

        void setMaxInitialLineLength(Integer maxInitialLineLength) {
            this.maxInitialLineLength = maxInitialLineLength;
        }
    }

    /**
     * Configurations for the HTTP/2.
     */
    static class Http2 {
        /**
         * The initial connection-level HTTP/2 flow control window size.
         */
        @Nullable
        private String initialConnectionWindowSize;

        /**
         * The initial stream-level HTTP/2 flow control window size.
         */
        @Nullable
        private String initialStreamWindowSize;

        /**
         * The maximum size of HTTP/2 frame that can be received.
         */
        @Nullable
        private String maxFrameSize;

        /**
         * The maximum size of headers that can be received.
         */
        @Nullable
        private String maxHeaderListSize;

        @Nullable
        String getInitialConnectionWindowSize() {
            return initialConnectionWindowSize;
        }

        void setInitialConnectionWindowSize(String initialConnectionWindowSize) {
            this.initialConnectionWindowSize = initialConnectionWindowSize;
        }

        @Nullable
        String getInitialStreamWindowSize() {
            return initialStreamWindowSize;
        }

        void setInitialStreamWindowSize(String initialStreamWindowSize) {
            this.initialStreamWindowSize = initialStreamWindowSize;
        }

        @Nullable
        String getMaxFrameSize() {
            return maxFrameSize;
        }

        void setMaxFrameSize(String maxFrameSize) {
            this.maxFrameSize = maxFrameSize;
        }

        @Nullable
        String getMaxHeaderListSize() {
            return maxHeaderListSize;
        }

        void setMaxHeaderListSize(String maxHeaderListSize) {
            this.maxHeaderListSize = maxHeaderListSize;
        }
    }

    /**
     * Configurations for the access log.
     */
    static class AccessLog {

        /**
         * The access log type that is supposed to be one of
         * {@code "common"} {@code "combined"} or {@code "custom"}.
         */
        @Nullable
        private String type;

        /**
         * The access log format string.
         */
        @Nullable
        private String format;

        @Nullable
        String getType() {
            return type;
        }

        void setType(String type) {
            this.type = type;
        }

        @Nullable
        String getFormat() {
            return format;
        }

        void setFormat(String format) {
            this.format = format;
        }
    }
}
