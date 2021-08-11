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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import com.codahale.metrics.json.MetricsModule;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;

import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;

/**
 * Settings for armeria servers. For example:
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
 *   compression:
 *     enabled: true
 *     mime-types: text/*, application/json
 *     excluded-user-agents: some-user-agent, another-user-agent
 *     min-response-size: 1KB
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
     * Configurations for the HTTP content encoding.
     */
    public static class Compression {
        /**
         * Specifies whether the HTTP content encoding is enabled.
         */
        private boolean enabled;

        /**
         * The MIME Types of an HTTP response which are applicable for the HTTP content encoding.
         */
        private String[] mimeTypes = {
                "text/html", "text/xml", "text/plain", "text/css", "text/javascript",
                "application/javascript", "application/json", "application/xml"
        };

        /**
         * The {@code "user-agent"} header values which are not applicable for the HTTP content encoding.
         */
        @Nullable
        private String[] excludedUserAgents;

        /**
         * The minimum bytes for encoding the content of an HTTP response.
         */
        private String minResponseSize = "1024";

        /**
         * Returns {@code true} if the HTTP content encoding is enabled.
         */
        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Sets whether the HTTP content encoding is enabled.
         */
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Returns the MIME Types of an HTTP response which are applicable for the HTTP content encoding.
         */
        public String[] getMimeTypes() {
            return mimeTypes;
        }

        /**
         * Sets the MIME Types of an HTTP response which are applicable for the HTTP content encoding.
         */
        public void setMimeTypes(String[] mimeTypes) {
            this.mimeTypes = mimeTypes;
        }

        /**
         * Returns the {@code "user-agent"} header values which are not applicable for the HTTP content
         * encoding.
         */
        @Nullable
        public String[] getExcludedUserAgents() {
            return excludedUserAgents;
        }

        /**
         * Sets the {@code "user-agent"} header values which are not applicable for the HTTP content encoding.
         */
        public void setExcludedUserAgents(String[] excludedUserAgents) {
            this.excludedUserAgents = excludedUserAgents;
        }

        /**
         * Returns the minimum bytes for encoding the content of an HTTP response.
         */
        public String getMinResponseSize() {
            return minResponseSize;
        }

        /**
         * Sets the minimum bytes for encoding the content of an HTTP response.
         */
        public void setMinResponseSize(String minResponseSize) {
            this.minResponseSize = minResponseSize;
        }
    }

    /**
     * Whether to auto configure and start the Armeria server.
     * The default is {@code true}.
     */
    private boolean serverEnabled = true;

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
    @NestedConfigurationProperty
    private Ssl ssl;

    /**
     * Compression configuration that the {@link Server} uses.
     */
    @Nullable
    private Compression compression;

    /**
     * Returns the {@link Port}s of the {@link Server}.
     */
    public List<Port> getPorts() {
        return ports;
    }

    /**
     * Sets the {@link Port}s of the {@link Server}.
     */
    public void setPorts(List<Port> ports) {
        this.ports = ports;
    }

    /**
     * Returns the path of the {@link HealthCheckService}.
     */
    @Nullable
    public String getHealthCheckPath() {
        return healthCheckPath;
    }

    /**
     * Sets the path of the {@link HealthCheckService}.
     */
    public void setHealthCheckPath(@Nullable String healthCheckPath) {
        this.healthCheckPath = healthCheckPath;
    }

    /**
     * Returns the path of the {@link DocService}.
     */
    @Nullable
    public String getDocsPath() {
        return docsPath;
    }

    /**
     * Sets the path of the {@link DocService}.
     */
    public void setDocsPath(@Nullable String docsPath) {
        this.docsPath = docsPath;
    }

    /**
     * Returns the path of the metrics exposition service. {@link PrometheusExpositionService} will be used if
     * {@link PrometheusMeterRegistry} is available. Otherwise, Dropwizard's {@link MetricsModule} will be used
     * if {@link DropwizardMeterRegistry} is available.
     */
    @Nullable
    public String getMetricsPath() {
        return metricsPath;
    }

    /**
     * Sets the path of the metrics exposition service. {@link PrometheusExpositionService} will be used if
     * {@link PrometheusMeterRegistry} is available. Otherwise, Dropwizard's {@link MetricsModule} will be used
     * if {@link DropwizardMeterRegistry} is available.
     */
    public void setMetricsPath(@Nullable String metricsPath) {
        this.metricsPath = metricsPath;
    }

    /**
     * Returns the number of milliseconds to wait for active requests to go end before shutting down.
     *
     * @see #getGracefulShutdownTimeoutMillis()
     * @see ServerBuilder#gracefulShutdownTimeout(Duration, Duration)
     */
    public long getGracefulShutdownQuietPeriodMillis() {
        return gracefulShutdownQuietPeriodMillis;
    }

    /**
     * Sets the number of milliseconds to wait for active requests to go end before shutting down.
     *
     * @see #setGracefulShutdownTimeoutMillis(long)
     * @see ServerBuilder#gracefulShutdownTimeout(Duration, Duration)
     */
    public void setGracefulShutdownQuietPeriodMillis(long gracefulShutdownQuietPeriodMillis) {
        this.gracefulShutdownQuietPeriodMillis = gracefulShutdownQuietPeriodMillis;
    }

    /**
     * Returns the number of milliseconds to wait before shutting down the server regardless of active requests.
     *
     * @see #getGracefulShutdownQuietPeriodMillis()
     * @see ServerBuilder#gracefulShutdownTimeout(Duration, Duration)
     */
    public long getGracefulShutdownTimeoutMillis() {
        return gracefulShutdownTimeoutMillis;
    }

    /**
     * Sets the number of milliseconds to wait before shutting down the server regardless of active requests.
     *
     * @see #setGracefulShutdownQuietPeriodMillis(long)
     * @see ServerBuilder#gracefulShutdownTimeout(Duration, Duration)
     */
    public void setGracefulShutdownTimeoutMillis(long gracefulShutdownTimeoutMillis) {
        this.gracefulShutdownTimeoutMillis = gracefulShutdownTimeoutMillis;
    }

    /**
     * Returns whether to enable metrics exposition service at the path specified via
     * {@link #setMetricsPath(String)}.
     */
    public boolean isEnableMetrics() {
        return enableMetrics;
    }

    /**
     * Sets whether to enable metrics exposition service at the path specified via
     * {@link #setMetricsPath(String)}.
     */
    public void setEnableMetrics(boolean enableMetrics) {
        this.enableMetrics = enableMetrics;
    }

    /**
     * Returns the {@link Ssl} configuration that the {@link Server} uses.
     */
    @Nullable
    public Ssl getSsl() {
        return ssl;
    }

    /**
     * Sets the {@link Ssl} configuration that the {@link Server} uses.
     */
    public void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }

    /**
     * Returns the HTTP content encoding configuration that the {@link Server} uses.
     */
    @Nullable
    public Compression getCompression() {
        return compression;
    }

    /**
     * Sets the HTTP content encoding configuration that the {@link Server} uses.
     */
    public void setCompression(Compression compression) {
        this.compression = compression;
    }
}
