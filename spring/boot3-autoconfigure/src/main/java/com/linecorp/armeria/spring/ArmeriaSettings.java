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

import java.net.InetAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

import com.codahale.metrics.json.MetricsModule;
import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.VirtualHost;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.prometheus.PrometheusExpositionService;

import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.netty.channel.EventLoopGroup;

/**
 * Settings for armeria servers. For example:
 * <pre>{@code
 * armeria:
 *   ports:
 *     - port: 8080
 *       protocols: HTTP
 *     - address: 127.0.0.1
 *       port: 8081
 *       protocols: HTTP
 *     - port: 8443
 *       protocols: HTTPS
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
 *   internal-services:
 *     port: 18080
 *     include: docs, health, metrics
 *   enable-auto-injection: true
 * }</pre>
 */
@ConfigurationProperties(prefix = "armeria")
@Validated
public class ArmeriaSettings {

    // TODO(ide) Adds virtualhost settings

    /**
     * Port and protocol settings.
     */
    public static class Port {
        /**
         * IP address to bind to. If not set, will bind to all addresses, e.g. {@code 0.0.0.0}.
         *
         * @deprecated Use {@link #address} instead.
         */
        @Deprecated
        @Nullable
        private String ip;

        /**
         * Network address to bind to. If not set, will bind to all addresses, e.g. {@code 0.0.0.0}.
         */
        @Nullable
        private InetAddress address;

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
         *
         * @deprecated Use {@link #getAddress()} instead.
         */
        @Deprecated
        @Nullable
        public String getIp() {
            return ip;
        }

        /**
         * Registers an IP address that the {@link Server} uses.
         *
         * @deprecated Use {@link #setAddress(InetAddress)} instead.
         */
        @Deprecated
        public Port setIp(String ip) {
            this.ip = ip;
            return this;
        }

        /**
         * Returns the network address that the {@link Server} uses.
         */
        @Nullable
        public InetAddress getAddress() {
            return address;
        }

        /**
         * Registers a network address that the {@link Server} uses.
         */
        public Port setAddress(InetAddress address) {
            this.address = address;
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

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("ip", ip)
                              .add("address", address)
                              .add("iface", iface)
                              .add("port", port)
                              .add("protocols", protocols)
                              .toString();
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
     * Properties for internal services such as {@link DocService}, {@link PrometheusExpositionService}, and
     * {@link HealthCheckService}.
     */
    public static class InternalServiceProperties extends Port {

        /**
         * The {@code include} properties to secure the internal services from normal
         * {@linkplain #getPorts() ports}.
         */
        @Nullable
        private List<InternalServiceId> include = InternalServiceId.defaultServiceIds();

        /**
         * Returns the {@code include} property to secure the HTTP endpoints from normal
         * {@linkplain #getPorts() ports}.
         */
        @Nullable
        public List<InternalServiceId> getInclude() {
            return include;
        }

        /**
         * Sets the IDs of the {@link HttpService}s to secure from normal {@linkplain #getPorts() ports}.
         *
         * <table>
         * <caption>Supported service IDs</caption>
         *   <tbody>
         *     <tr>
         *       <th>ID</th>
         *       <th>Service</th>
         *     </tr>
         *     <tr>
         *       <td>{@code docs}</td>
         *       <td>{@link DocService}</td>
         *     </tr>
         *     <tr>
         *       <td>{@code metrics}</td>
         *       <td>{@link PrometheusExpositionService}</td>
         *     </tr>
         *     <tr>
         *       <td>{@code health}</td>
         *       <td>{@link HealthCheckService}</td>
         *     </tr>
         *     <tr>
         *       <td>{@code actuator}</td>
         *       <td>To bind {@code WebOperationService}. Note that this option is only valid when
         *           {@code "armeria-spring-boot2-actuator-autoconfigure"} is activated.</td>
         *     </tr>
         *   </tbody>
         * </table>
         *
         * <p>{@link InternalServiceId#ALL} can be used to include all internal {@link HttpService}s.
         *
         * @see InternalServiceId
         */
        public void setInclude(List<InternalServiceId> include) {
            this.include = include;
        }
    }

    /**
     * Configurations for the access log.
     */
    public static class AccessLog {

        /**
         * The access log type that is supposed to be one of
         * {@code "common"}, {@code "combined"}, {@code "disabled"} or {@code "custom"}.
         * The default is {@code "disabled"}.
         */
        private String type = "disabled";

        /**
         * The access log format.
         */
        @Nullable
        private String format;

        /**
         * Returns the access log type.
         */
        public String getType() {
            return type;
        }

        /**
         * Sets the access log type that is supposed to be one of
         * {@code "common"}, {@code "combined"}, {@code "disabled"} or {@code "custom"}.
         */
        public void setType(String type) {
            this.type = type;
        }

        /**
         * Returns the access log format.
         * Please note that this format is used only when the specified {@code type} is {@code "custom"}.
         */
        @Nullable
        public String getFormat() {
            return format;
        }

        /**
         * Sets the access log format.
         * Please note that this format is used only when the specified {@code type} is {@code "custom"}.
         */
        public void setFormat(@Nullable String format) {
            this.format = format;
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
     * The context path to serve the requests on. If not set, requests will be served on the root context path.
     */
    @Nullable
    private String contextPath;

    /**
     * The path to serve health check requests on. Should correspond to what is
     * registered in the load balancer. If not set, health check service will not
     * be registered.
     */
    @Nullable
    private String healthCheckPath = "/internal/healthcheck";

    /**
     * The path to serve the documentation service on. Should not be exposed
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
     * The properties for internal services that should not be exposed to the external network.
     * If not specified, the paths such as {@link #getDocsPath()}, {@link #getMetricsPath()} and
     * {@link #getHealthCheckPath()} will be served on the normal service {@linkplain #getPorts() ports}.
     */
    @Nullable
    private InternalServiceProperties internalServices;

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
     * The number of threads for {@link EventLoopGroup} that the {@link Server} uses.
     */
    @Nullable
    private Integer workerGroup;

    /**
     * The number of threads dedicated to the execution of blocking tasks or invocations.
     */
    @Nullable
    private Integer blockingTaskExecutor;

    /**
     * The maximum allowed number of open connections.
     */
    @Nullable
    private Integer maxNumConnections;

    /**
     * The idle timeout of a connection for keep-alive.
     */
    @Nullable
    private Duration idleTimeout;

    /**
     * The interval of the HTTP/2 PING frame.
     */
    @Nullable
    private Duration pingInterval;

    /**
     * The maximum allowed age of a connection for keep-alive.
     */
    @Nullable
    private Duration maxConnectionAge;

    /**
     * The maximum allowed number of requests that can be served through one connection.
     */
    @Nullable
    private Integer maxNumRequestsPerConnection;

    /**
     * The initial connection-level HTTP/2 flow control window size.
     */
    @Nullable
    private String http2InitialConnectionWindowSize;

    /**
     * The initial stream-level HTTP/2 flow control window size.
     */
    @Nullable
    private String http2InitialStreamWindowSize;

    /**
     * The maximum number of concurrent streams per HTTP/2 connection.
     */
    @Nullable
    private Long http2MaxStreamsPerConnection;

    /**
     * The maximum size of HTTP/2 frame that can be received.
     */
    @Nullable
    private String http2MaxFrameSize;

    /**
     * The maximum size of headers that can be received.
     */
    @Nullable
    private String http2MaxHeaderListSize;

    /**
     * The maximum length of an HTTP/1 response initial line.
     */
    @Nullable
    private String http1MaxInitialLineLength;

    /**
     * The maximum length of all headers in an HTTP/1 response.
     */
    @Nullable
    private String http1MaxHeaderSize;

    /**
     * The maximum length of each chunk in an HTTP/1 response content.
     */
    @Nullable
    private String http1MaxChunkSize;

    /**
     * The {@link Server}'s access log configuration.
     */
    @Nullable
    private AccessLog accessLog;

    /**
     * The default access logger name for all {@link VirtualHost}s.
     */
    @Nullable
    private String accessLogger;

    /**
     * The timeout of a request.
     */
    @Nullable
    private Duration requestTimeout;

    /**
     * The maximum allowed length of the content decoded at the session layer.
     */
    @Nullable
    private String maxRequestLength;

    /**
     * Whether the verbose response mode is enabled.
     */
    @Nullable
    private Boolean verboseResponses;

    /**
     * Whether to inject dependencies in annotated services using {@link SpringDependencyInjector}.
     */
    private boolean enableAutoInjection;

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
     * Returns the context path of the {@link Server}.
     */
    @Nullable
    public String getContextPath() {
        return contextPath;
    }

    /**
     * Sets the context path to serve the requests on. If not set, requests will be served on the root context
     * path.
     */
    public void setContextPath(String contextPath) {
        this.contextPath = contextPath;
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
     * {@code armeria-prometheus1} module is added. Otherwise, Dropwizard's {@link MetricsModule} will be used
     * if {@link DropwizardMeterRegistry} is available.
     */
    @Nullable
    public String getMetricsPath() {
        return metricsPath;
    }

    /**
     * Sets the path of the metrics exposition service. {@link PrometheusExpositionService} will be used if
     * {@code armeria-prometheus1} module is added. Otherwise, Dropwizard's {@link MetricsModule} will be used
     * if {@link DropwizardMeterRegistry} is available.
     */
    public void setMetricsPath(@Nullable String metricsPath) {
        this.metricsPath = metricsPath;
    }

    /**
     * Sets the properties of internal services that should not be exposed to the external network.
     */
    public void setInternalServices(InternalServiceProperties internalServices) {
        this.internalServices = internalServices;
    }

    /**
     * Returns the properties of internal services that should not be exposed to the external network.
     */
    @Nullable
    public InternalServiceProperties getInternalServices() {
        return internalServices;
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

    /**
     * Returns the number of threads for {@link EventLoopGroup} that the {@link Server} uses.
     */
    @Nullable
    public Integer getWorkerGroup() {
        return workerGroup;
    }

    /**
     * Sets the number of threads for {@link EventLoopGroup} that the {@link Server} uses.
     */
    public void setWorkerGroup(@Nullable Integer workerGroup) {
        this.workerGroup = workerGroup;
    }

    /**
     * Returns the number of threads dedicated to the execution of blocking tasks or invocations.
     */
    @Nullable
    public Integer getBlockingTaskExecutor() {
        return blockingTaskExecutor;
    }

    /**
     * Sets the number of threads dedicated to the execution of blocking tasks or invocations.
     */
    public void setBlockingTaskExecutor(@Nullable Integer blockingTaskExecutor) {
        this.blockingTaskExecutor = blockingTaskExecutor;
    }

    /**
     * Returns the maximum allowed number of open connections.
     */
    @Nullable
    public Integer getMaxNumConnections() {
        return maxNumConnections;
    }

    /**
     * Sets the maximum allowed number of open connections.
     */
    public void setMaxNumConnections(@Nullable Integer maxNumConnections) {
        this.maxNumConnections = maxNumConnections;
    }

    /**
     * Returns the idle timeout of a connection for keep-alive.
     */
    @Nullable
    public Duration getIdleTimeout() {
        return idleTimeout;
    }

    /**
     * Sets the idle timeout of a connection for keep-alive.
     */
    public void setIdleTimeout(@Nullable Duration idleTimeout) {
        this.idleTimeout = idleTimeout;
    }

    /**
     * Returns the interval of the HTTP/2 PING frame.
     */
    @Nullable
    public Duration getPingInterval() {
        return pingInterval;
    }

    /**
     * Sets the interval of the HTTP/2 PING frame.
     */
    public void setPingInterval(@Nullable Duration pingInterval) {
        this.pingInterval = pingInterval;
    }

    /**
     * Returns the maximum allowed age of a connection for keep-alive.
     */
    @Nullable
    public Duration getMaxConnectionAge() {
        return maxConnectionAge;
    }

    /**
     * Sets the maximum allowed age of a connection for keep-alive.
     */
    public void setMaxConnectionAge(@Nullable Duration maxConnectionAge) {
        this.maxConnectionAge = maxConnectionAge;
    }

    /**
     * Returns the maximum allowed number of requests that can be served through one connection.
     */
    @Nullable
    public Integer getMaxNumRequestsPerConnection() {
        return maxNumRequestsPerConnection;
    }

    /**
     * Sets the maximum allowed number of requests that can be served through one connection.
     */
    public void setMaxNumRequestsPerConnection(@Nullable Integer maxNumRequestsPerConnection) {
        this.maxNumRequestsPerConnection = maxNumRequestsPerConnection;
    }

    /**
     * Returns the initial connection-level HTTP/2 flow control window size.
     */
    @Nullable
    public String getHttp2InitialConnectionWindowSize() {
        return http2InitialConnectionWindowSize;
    }

    /**
     * Sets the initial connection-level HTTP/2 flow control window size.
     */
    public void setHttp2InitialConnectionWindowSize(@Nullable String http2InitialConnectionWindowSize) {
        this.http2InitialConnectionWindowSize = http2InitialConnectionWindowSize;
    }

    /**
     * Returns the initial stream-level HTTP/2 flow control window size.
     */
    @Nullable
    public String getHttp2InitialStreamWindowSize() {
        return http2InitialStreamWindowSize;
    }

    /**
     * Sets the initial stream-level HTTP/2 flow control window size.
     */
    public void setHttp2InitialStreamWindowSize(@Nullable String http2InitialStreamWindowSize) {
        this.http2InitialStreamWindowSize = http2InitialStreamWindowSize;
    }

    /**
     * Returns the maximum number of concurrent streams per HTTP/2 connection.
     */
    @Nullable
    public Long getHttp2MaxStreamsPerConnection() {
        return http2MaxStreamsPerConnection;
    }

    /**
     * Sets the maximum number of concurrent streams per HTTP/2 connection.
     */
    public void setHttp2MaxStreamsPerConnection(@Nullable Long http2MaxStreamsPerConnection) {
        this.http2MaxStreamsPerConnection = http2MaxStreamsPerConnection;
    }

    /**
     * Returns the maximum size of HTTP/2 frame that can be received.
     */
    @Nullable
    public String getHttp2MaxFrameSize() {
        return http2MaxFrameSize;
    }

    /**
     * Sets the maximum size of HTTP/2 frame that can be received.
     */
    public void setHttp2MaxFrameSize(@Nullable String http2MaxFrameSize) {
        this.http2MaxFrameSize = http2MaxFrameSize;
    }

    /**
     * Returns the maximum size of headers that can be received.
     */
    @Nullable
    public String getHttp2MaxHeaderListSize() {
        return http2MaxHeaderListSize;
    }

    /**
     * Sets the maximum size of headers that can be received.
     */
    public void setHttp2MaxHeaderListSize(@Nullable String http2MaxHeaderListSize) {
        this.http2MaxHeaderListSize = http2MaxHeaderListSize;
    }

    /**
     * Returns the maximum length of an HTTP/1 response initial line.
     */
    @Nullable
    public String getHttp1MaxInitialLineLength() {
        return http1MaxInitialLineLength;
    }

    /**
     * Sets the maximum length of an HTTP/1 response initial line.
     */
    public void setHttp1MaxInitialLineLength(@Nullable String http1MaxInitialLineLength) {
        this.http1MaxInitialLineLength = http1MaxInitialLineLength;
    }

    /**
     * Returns the maximum length of all headers in an HTTP/1 response.
     */
    @Nullable
    public String getHttp1MaxHeaderSize() {
        return http1MaxHeaderSize;
    }

    /**
     * Sets the maximum length of all headers in an HTTP/1 response.
     */
    public void setHttp1MaxHeaderSize(@Nullable String http1MaxHeaderSize) {
        this.http1MaxHeaderSize = http1MaxHeaderSize;
    }

    /**
     * Returns the maximum length of each chunk in an HTTP/1 response content.
     */
    @Nullable
    public String getHttp1MaxChunkSize() {
        return http1MaxChunkSize;
    }

    /**
     * Sets the maximum length of each chunk in an HTTP/1 response content.
     */
    public void setHttp1MaxChunkSize(@Nullable String http1MaxChunkSize) {
        this.http1MaxChunkSize = http1MaxChunkSize;
    }

    /**
     * Returns the {@link Server}'s access log configuration.
     */
    @Nullable
    public AccessLog getAccessLog() {
        return accessLog;
    }

    /**
     * Sets the {@link Server}'s access log configuration.
     */
    public void setAccessLog(@Nullable AccessLog accessLog) {
        this.accessLog = accessLog;
    }

    /**
     * Returns the default access logger name for all {@link VirtualHost}s.
     */
    @Nullable
    public String getAccessLogger() {
        return accessLogger;
    }

    /**
     * Sets the default access logger name for all {@link VirtualHost}s.
     */
    public void setAccessLogger(@Nullable String accessLogger) {
        this.accessLogger = accessLogger;
    }

    /**
     * Returns the timeout of a request.
     */
    @Nullable
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Sets the timeout of a request.
     */
    public void setRequestTimeout(@Nullable Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    /**
     * Returns the maximum allowed length of the content decoded at the session layer.
     */
    @Nullable
    public String getMaxRequestLength() {
        return maxRequestLength;
    }

    /**
     * Sets the maximum allowed length of the content decoded at the session layer.
     */
    public void setMaxRequestLength(@Nullable String maxRequestLength) {
        this.maxRequestLength = maxRequestLength;
    }

    /**
     * Returns whether the verbose response mode is enabled.
     */
    @Nullable
    public Boolean getVerboseResponses() {
        return verboseResponses;
    }

    /**
     * Sets whether the verbose response mode is enabled.
     */
    public void setVerboseResponses(@Nullable Boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
    }

    /**
     * Returns whether to apply {@link SpringDependencyInjector} automatically.
     */
    public boolean isEnableAutoInjection() {
        return enableAutoInjection;
    }

    /**
     * Sets whether to apply {@link SpringDependencyInjector} automatically.
     */
    public void setEnableAutoInjection(boolean enableAutoInjection) {
        this.enableAutoInjection = enableAutoInjection;
    }
}
