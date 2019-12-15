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
package com.linecorp.armeria.dropwizard;

import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.net.ssl.SSLException;
import javax.validation.Valid;
import javax.validation.constraints.Min;
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
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.DropwizardMeterRegistries;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.dropwizard.connector.ArmeriaHttpConnectorFactory;
import com.linecorp.armeria.dropwizard.connector.ArmeriaServerDecorator;
import com.linecorp.armeria.dropwizard.logging.AccessLogWriterFactory;
import com.linecorp.armeria.dropwizard.logging.CommonAccessLogWriterFactory;
import com.linecorp.armeria.server.ServerBuilder;
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
    // TODO: This class could be stripped down to the essential fields. Implement ServerFactory instead.

    public static final String TYPE = "armeria";
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerFactory.class);

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
     * @param connectorFactory {@code null} or {@link ConnectorFactory}. If non-null must be an instance of
     *                         an {@link ArmeriaServerDecorator}
     * @param writerFactory {@code null} or {@link AccessLogWriterFactory}
     * @param meterRegistry {@code null} or {@link MeterRegistry}
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
            logger.trace("Setting up Armeria AccessLogWriter");
            sb.accessLogWriter(writerFactory.getWriter(), true);
        } else {
            logger.info("Armeria access logs will not be written");
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
    private @Min(0) int maxNumConnections = Flags.maxNumConnections();
    @JsonProperty
    private boolean dateHeaderEnabled = true;
    @JsonProperty
    private boolean serverHeaderEnabled = true;
    @JsonProperty
    private boolean verboseResponses;
    @JsonProperty
    @Nullable
    private String defaultHostname;
    @JsonIgnore
    @Nullable
    private transient ServerBuilder serverBuilder;

    /**
     * Sets up the Armeria ServerBuilder with values from the Dropwizard Configuration.
     * Ref <a href="https://line.github.io/armeria/advanced-production-checklist.html">Production Checklist</a>
     *
     * @param serverBuilder A non-production ready ServerBuilder
     * @return A production-ready ServerBuilder
     */
    @VisibleForTesting
    ServerBuilder decorateServerBuilderFromConfig(final ServerBuilder serverBuilder) {
        Objects.requireNonNull(serverBuilder);
        final ScheduledThreadPoolExecutor blockingTaskExecutor = new ScheduledThreadPoolExecutor(
                getMaxThreads(),
                ThreadFactories.newThreadFactory("armeria-dropwizard-blocking-tasks", true));
        blockingTaskExecutor.setKeepAliveTime(60, TimeUnit.SECONDS);
        blockingTaskExecutor.allowCoreThreadTimeOut(true);

        serverBuilder.maxNumConnections(getMaxNumConnections())
                     .blockingTaskExecutor(blockingTaskExecutor, true)
                     .maxRequestLength(maxRequestLength.toBytes())
                     .idleTimeoutMillis(getIdleThreadTimeout().toMilliseconds())
                     .gracefulShutdownTimeout(
                             Duration.ofMillis(getShutdownGracePeriod().toMilliseconds()),
                             Duration.ofMillis(getShutdownGracePeriod().toMilliseconds()))
                     .verboseResponses(hasVerboseResponses());
        if (!isDateHeaderEnabled()) {
            serverBuilder.disableDateHeader();
        }
        if (!isServerHeaderEnabled()) {
            serverBuilder.disableServerHeader();
        }
        Optional.ofNullable(getDefaultHostname()).ifPresent(serverBuilder::defaultHostname);
        // TODO: Add more items to server builder via Configuration
        return serverBuilder;
    }

    @Nullable
    private String getDefaultHostname() {
        return defaultHostname;
    }

    @JsonGetter("verboseResponses")
    private boolean hasVerboseResponses() {
        return verboseResponses;
    }

    public void setVerboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
    }

    public boolean isDateHeaderEnabled() {
        return dateHeaderEnabled;
    }

    public void setDateHeaderEnabled(final boolean dateHeaderEnabled) {
        this.dateHeaderEnabled = dateHeaderEnabled;
    }

    public boolean isServerHeaderEnabled() {
        return serverHeaderEnabled;
    }

    public void setServerHeaderEnabled(final boolean serverHeaderEnabled) {
        this.serverHeaderEnabled = serverHeaderEnabled;
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

    public int getMaxNumConnections() {
        return maxNumConnections;
    }

    public void setMaxNumConnections(final int maxNumConnections) {
        this.maxNumConnections = maxNumConnections;
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
        logger.debug("Building Armeria Server");
        final ServerBuilder serverBuilder = com.linecorp.armeria.server.Server.builder();
        try {
            decorateServerBuilder(
                    serverBuilder, this.connector, accessLogWriter,
                    metricRegistry != null ? DropwizardMeterRegistries.newRegistry(metricRegistry) : null);
        } catch (SSLException | CertificateException e) {
            logger.error("Unable to define TLS Server", e);
            // TODO: Throw an exception?
        }

        final JettyService jettyService = getJettyService(server);
        return decorateServerBuilderFromConfig(serverBuilder)
                .serviceUnder("/", jettyService);
    }
}
