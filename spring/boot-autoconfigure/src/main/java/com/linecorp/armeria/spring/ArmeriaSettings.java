/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.spring;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.metric.MetricCollectingService;

/**
 * Settings for armeria servers, e.g.,
 * <pre>{@code
 * armeria:
 *   ports:
 *     - port: 8080
 *       protocol: HTTP
 *     - ip: 127.0.0.1
 *       port: 8081
 *       protocol:HTTP
 *     - port: 8443
 *       protocol: HTTPS
 *   ssl:
 *     key-alias: "host.name.com"
 *     key-store: "keystore.jks"
 *     key-store-password: "changeme"
 *     trust-store: "truststore.jks"
 *     trust-store-password: "changeme"
 * }</pre>
 * TODO(ide) Adds virtualhost settings
 */
@ConfigurationProperties(prefix = "armeria")
@Validated
public class ArmeriaSettings {

    /**
     * Port and protocol settings.
     */
    public static class Port {
        /**
         * IP address to bind to. If not set, will bind to all addresses, e.g. {@code 0.0.0.0}.
         */
        private String ip;

        /**
         * Network interface to bind to. If not set, will bind to the first detected network interface.
         */
        private String iface;

        /**
         * Port that {@link Server} binds to.
         */
        private int port;

        /**
         * Protocol that will be used in this ip/iface and port.
         */
        private List<SessionProtocol> protocols;

        /**
         * Returns the IP address that the {@link Server} uses.
         */
        @Nullable
        public String getIp() {
            return ip;
        }

        /**
         * Registers an IP address that the {@link Server} uses.
         */
        public Port setIp(String ip) {
            this.ip = ip;
            return this;
        }

        /**
         * Returns the network interface that the {@link Server} uses.
         */
        @Nullable
        public String getIface() {
            return iface;
        }

        /**
         * Registers a network interface that the {@link Server} uses.
         */
        public Port setIface(String iface) {
            this.iface = iface;
            return this;
        }

        /**
         * Returns the port that the {@link Server} uses.
         */
        public int getPort() {
            return port;
        }

        /**
         * Registers a port that the {@link Server} uses.
         */
        public Port setPort(int port) {
            this.port = port;
            return this;
        }

        /**
         * Returns the list of {@link SessionProtocol}s that the {@link Server} uses.
         */
        @Nullable
        public List<SessionProtocol> getProtocols() {
            return protocols;
        }

        /**
         * Registers a list of {@link SessionProtocol}s that the {@link Server} uses.
         */
        public Port setProtocols(List<SessionProtocol> protocols) {
            this.protocols = ImmutableList.copyOf(protocols);
            return this;
        }

        /**
         * Registers a {@link SessionProtocol} that the {@link Server} uses.
         */
        public Port setProtocol(SessionProtocol protocol) {
            protocols = ImmutableList.of(protocol);
            return this;
        }
    }

    /**
     * The ports to listen on for requests. If not specified, will listen on
     * port 8080 for HTTP (not SSL).
     */
    private List<Port> ports = new ArrayList<>();

    /**
     * The path to serve health check requests on. Should correspond to what is
     * registered in the load balancer. If not set, health check service will not
     * be registered.
     */
    @Nullable
    private String healthCheckPath = "/internal/healthcheck";

    /**
     * The path to serve thrift service documentation on. Should not be exposed
     * to the external network. If not set, documentation service will not be
     * registered.
     */
    @Nullable
    private String docsPath = "/internal/docs/";

    /**
     * The path to serve a json dump of instantaneous metrics. Should not be
     * exposed to the external network. If not set, metrics will not be exported
     * on an http path (any registered reporters will still function).
     */
    @Nullable
    private String metricsPath = "/internal/metrics";

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
     * Whether to decorate all services with {@link MetricCollectingService}.
     * The default is {@code true}.
     */
    private boolean enableMetrics = true;

    /**
     * SSL configuration that the {@link Server} uses.
     */
    @Nullable
    private Ssl ssl;

    public List<Port> getPorts() {
        return ports;
    }

    public void setPorts(List<Port> ports) {
        this.ports = ports;
    }

    @Nullable
    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    public void setHealthCheckPath(@Nullable String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    @Nullable
    public String getDocsPath() {
        return docsPath;
    }

    public void setDocsPath(@Nullable String docsPath) {
        this.docsPath = docsPath;
    }

    @Nullable
    public String getMetricsPath() {
        return metricsPath;
    }

    public void setMetricsPath(@Nullable String metricsPath) {
        this.metricsPath = metricsPath;
    }

    public long getGracefulShutdownQuietPeriodMillis() {
        return gracefulShutdownQuietPeriodMillis;
    }

    public void setGracefulShutdownQuietPeriodMillis(long gracefulShutdownQuietPeriodMillis) {
        this.gracefulShutdownQuietPeriodMillis = gracefulShutdownQuietPeriodMillis;
    }

    public long getGracefulShutdownTimeoutMillis() {
        return gracefulShutdownTimeoutMillis;
    }

    public void setGracefulShutdownTimeoutMillis(long gracefulShutdownTimeoutMillis) {
        this.gracefulShutdownTimeoutMillis = gracefulShutdownTimeoutMillis;
    }

    public boolean isEnableMetrics() {
        return enableMetrics;
    }

    public void setEnableMetrics(boolean enableMetrics) {
        this.enableMetrics = enableMetrics;
    }

    /**
     * Returns the SSL that the {@link Server} uses.
     */
    @Nullable
    public Ssl getSsl() {
        return ssl;
    }

    /**
     * Registers a {@link Ssl} that the {@link Server} uses.
     */
    public void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }
}
