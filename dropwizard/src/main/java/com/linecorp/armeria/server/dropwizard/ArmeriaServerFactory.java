/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.dropwizard;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.DropwizardMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfig;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaHttpConnectorFactory;
import com.linecorp.armeria.server.dropwizard.connector.ArmeriaServerDecorator;
import com.linecorp.armeria.server.dropwizard.logging.AccessLogWriterFactory;
import com.linecorp.armeria.server.dropwizard.logging.CommonAccessLogWriterFactory;
import com.linecorp.armeria.server.jetty.JettyService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.ContextRoutingHandler;
import io.dropwizard.server.SimpleServerFactory;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Size;
import io.dropwizard.validation.MinSize;
import io.micrometer.core.instrument.MeterRegistry;

@JsonTypeName(ArmeriaServerFactory.TYPE)
public class ArmeriaServerFactory extends SimpleServerFactory {

    public static final String TYPE = "armeria";
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmeriaServerFactory.class);

    /**
     * Wrap a {@link Server} in a {@link JettyService}.
     *
     * @param jettyServer An instance of a Jetty {@link Server}
     * @return Armeria {@link JettyService} for the provided jettyServer
     */
    @JsonIgnore
    public static JettyService getJettyService(final Server jettyServer) {
        Objects.requireNonNull(jettyServer, "Armeria cannot build a service from a null server");
        return JettyService.forServer(jettyServer);
    }

    /**
     * Builds on a {@link ServerBuilder}.
     *
     * @param sb An instance of a {@link ServerBuilder}
     * @param connectorFactory Null or {@link ConnectorFactory}. If non-null, must be an instance of
     *                          an {@link ArmeriaServerDecorator}
     * @param writerFactory Null or {@link AccessLogWriterFactory}
     * @param meterRegistry Null or {@link MeterRegistry}
     * @throws SSLException Thrown when configuring TLS
     * @throws CertificateException Thrown when validating certificates
     */
    public static ServerBuilder decorateServerBuilder(final ServerBuilder sb,
                                                      @Nullable final ConnectorFactory connectorFactory,
                                                      @Nullable final AccessLogWriterFactory writerFactory,
                                                      @Nullable final MeterRegistry meterRegistry)
            throws SSLException, CertificateException {
        Objects.requireNonNull(sb, "builder to decorate must not be null");
        if (connectorFactory != null) {
            if (!(connectorFactory instanceof ArmeriaServerDecorator)) {
                throw new ClassCastException("server.connector.type must be an instance of " +
                                             ArmeriaServerDecorator.class.getName());
            }
            ((ArmeriaServerDecorator) connectorFactory).decorate(sb);
        }
        if (meterRegistry != null) {
            sb.meterRegistry(meterRegistry);
        }
        if (writerFactory != null && !writerFactory.getWriter()
                                                   .equals(AccessLogWriter.disabled())) {
            LOGGER.trace("Setting up Armeria AccessLogWriter");
            sb.accessLogWriter(writerFactory.getWriter(), true);
        } else {
            LOGGER.info("Armeria access logs will not be written");
            sb.accessLogWriter(AccessLogWriter.disabled(), true);
        }
        return sb;
    }

    @JsonProperty
    private @Valid ConnectorFactory connector = ArmeriaHttpConnectorFactory.build();
    @JsonProperty
    private @Valid @NotNull AccessLogWriterFactory accessLogWriter = new CommonAccessLogWriterFactory();
    @JsonProperty
    private boolean jerseyEnabled = true;
    @JsonProperty
    private @MinSize(0) Size maxRequestLength = Size.bytes(Flags.defaultMaxRequestLength());
    @JsonProperty
    private boolean disableDateHeader;
    @JsonProperty
    private boolean disableServerHeader;
    @JsonProperty
    private boolean verboseResponses;
    @JsonProperty
    @Nullable
    private String defaultHostname;
    @JsonIgnore
    private transient ServerBuilder serverBuilder;

    public ArmeriaServerFactory() {
    }

    /**
     * Sets up the Armeria ServerBuilder with values from the Dropwizard Configuration.
     * Ref <a href="https://line.github.io/armeria/advanced-production-checklist.html">Production Checklist</a>
     *
     * @param serverBuilder A non-production ready ServerBuilder
     * @return A production-ready ServerBuilder
     */
    // visible for testing
    ServerBuilder decorateServerBuilderFromConfig(final ServerBuilder serverBuilder) {
        Objects.requireNonNull(serverBuilder);
        serverBuilder.maxNumConnections(getMaxQueuedRequests());
        serverBuilder.blockingTaskExecutor(Executors.newFixedThreadPool(getMaxThreads()), true);
        serverBuilder.maxRequestLength(maxRequestLength.toBytes());
        serverBuilder.idleTimeoutMillis(getIdleThreadTimeout().toMilliseconds());
        serverBuilder.gracefulShutdownTimeout(
                Duration.ofSeconds(30L),
                Duration.ofMillis(getShutdownGracePeriod().toMilliseconds()));
        if (isDateHeaderDisabled()) {
            serverBuilder.disableDateHeader();
        }
        if (isServerHeaderDisabled()) {
            serverBuilder.disableServerHeader();
        }
        serverBuilder.verboseResponses(hasVerboseResponses());
        Optional.ofNullable(getDefaultHostname()).ifPresent(serverBuilder::defaultHostname);
        // TODO: Add more items to server builder via Configuration
        return serverBuilder;
    }

    private String getDefaultHostname() {
        return defaultHostname;
    }

    @JsonGetter("verboseResponses")
    private boolean hasVerboseResponses() {
        return verboseResponses;
    }

    private void setVerboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
    }

    @JsonGetter("disableServerHeader")
    private boolean isServerHeaderDisabled() {
        return disableServerHeader;
    }

    @JsonGetter("disableDateHeader")
    public boolean isDateHeaderDisabled() {
        return disableDateHeader;
    }

    @JsonSetter("disableDateHeader")
    public void disableDateHeaderDisabled(boolean disabled) {
        this.disableDateHeader = disabled;
    }

    @JsonSetter("disableServerHeader")
    public void setDisableServerHeader(boolean disabled) {
        this.disableServerHeader = disabled;
    }

    @Override
    public ConnectorFactory getConnector() {
        return connector;
    }

    @Override
    public void setConnector(final ConnectorFactory factory) {
        connector = Objects.requireNonNull(factory, "server.connector");
    }

    public boolean isJerseyEnabled() {
        return jerseyEnabled;
    }

    public void setJerseyEnabled(final boolean jerseyEnabled) {
        this.jerseyEnabled = jerseyEnabled;
    }

    public AccessLogWriterFactory getAccessLogWriter() {
        return accessLogWriter;
    }

    /**
     * Sets an {@link AccessLogWriter} onto this ServerFactory.
     *
     * @param accessLogWriter - an instance of a {#link AccessLogWriter}
     */
    public void setAccessLogWriter(final @Valid AccessLogWriterFactory accessLogWriter) {
        this.accessLogWriter = Objects.requireNonNull(
                accessLogWriter, "server[type=\"" + TYPE + "\"].accessLogWriter");
    }

    public Size getMaxRequestLength() {
        return maxRequestLength;
    }

    public void setMaxRequestLength(final Size maxRequestLength) {
        this.maxRequestLength = maxRequestLength;
    }

    @JsonIgnore
    public ServerBuilder getServerBuilder() {
        return serverBuilder;
    }

    @Override
    public Server build(final Environment environment) {
        printBanner(environment.getName());
        final MetricRegistry metrics = environment.metrics();
        final ThreadPool threadPool = createThreadPool(metrics);
        final Server server = buildServer(environment.lifecycle(), threadPool);

        final JerseyEnvironment jersey = environment.jersey();
        if (!isJerseyEnabled()) {
            jersey.disable();
        }

        addDefaultHandlers(server, environment, metrics);
        serverBuilder = getArmeriaServerBuilder(server, connector, metrics);
        return server;
    }

    @JsonIgnore
    public ServerConfig getServerConfig() {
        return serverBuilder.build().config();
    }

    private void addDefaultHandlers(final Server server, final Environment environment,
                                    final MetricRegistry metrics) {
        final JerseyEnvironment jersey = environment.jersey();
        final Handler applicationHandler = createAppServlet(
                server,
                jersey,
                environment.getObjectMapper(),
                environment.getValidator(),
                environment.getApplicationContext(),
                environment.getJerseyServletContainer(),
                metrics);
        final Handler adminHandler = createAdminServlet(
                server,
                environment.getAdminContext(),
                metrics,
                environment.healthChecks());
        final ContextRoutingHandler routingHandler = new ContextRoutingHandler(ImmutableMap.of(
                getApplicationContextPath(), applicationHandler,
                getAdminContextPath(), adminHandler));
        final Handler gzipHandler = buildGzipHandler(routingHandler);
        server.setHandler(addStatsHandler(addRequestLog(server, gzipHandler, environment.getName())));
    }

    private ServerBuilder getArmeriaServerBuilder(final Server server,
                                                  @Nullable final ConnectorFactory connector,
                                                  @Nullable final MetricRegistry metricRegistry) {
        LOGGER.debug("Building Armeria Server");
        final JettyService jettyService = getJettyService(server);
        final ServerBuilder serverBuilder = com.linecorp.armeria.server.Server
                .builder()
                .serviceUnder("/", jettyService);
        try {
            decorateServerBuilder(serverBuilder, this.connector, accessLogWriter,
                                  DropwizardMeterRegistries.newRegistry(metricRegistry));
        } catch (SSLException | CertificateException e) {
            LOGGER.error("Unable to define TLS Server", e);
            // TODO: Throw an exception?
        }
        return decorateServerBuilderFromConfig(serverBuilder);
    }
}
