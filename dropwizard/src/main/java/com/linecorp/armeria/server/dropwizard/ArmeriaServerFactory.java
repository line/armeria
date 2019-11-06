package com.linecorp.armeria.server.dropwizard;

import java.io.File;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;
import javax.validation.Valid;
import javax.validation.constraints.NotNull;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.metric.DropwizardMeterRegistries;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.jetty.JettyService;

import com.linecorp.armeria.server.armeria.logging.AccessLogWriterFactory;
import com.linecorp.armeria.server.armeria.logging.CommonAccessLogWriterFactory;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.jetty.ConnectorFactory;
import io.dropwizard.jetty.ContextRoutingHandler;
import io.dropwizard.server.SimpleServerFactory;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Size;
import io.dropwizard.validation.MinSize;

@JsonTypeName(ArmeriaServerFactory.TYPE)
public class ArmeriaServerFactory extends SimpleServerFactory {

    public static final String TYPE = "armeria";
    private static final Logger LOGGER = LoggerFactory.getLogger(ArmeriaServerFactory.class);

    @Valid
    @JsonProperty
    private ConnectorFactory connector = ArmeriaHttpConnectorFactory.build();

    @Valid
    @NotNull
    @JsonProperty
    private AccessLogWriterFactory accessLogWriter = new CommonAccessLogWriterFactory();

    @JsonProperty
    private boolean jerseyEnabled = true;

    @JsonProperty
    @MinSize(value = 0)
    private Size maxRequestLength = Size.bytes(Flags.defaultMaxRequestLength());

    @JsonIgnore
    private transient ServerBuilder serverBuilder;

    public ArmeriaServerFactory() {
    }

    @Override
    public ConnectorFactory getConnector() {
        return this.connector;
    }

    @Override
    public void setConnector(final ConnectorFactory factory) {
        this.connector = Objects.requireNonNull(factory, "server.connector");
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

    public void setAccessLogWriter(@Valid final AccessLogWriterFactory accessLogWriter) {
        this.accessLogWriter = Objects.requireNonNull(accessLogWriter, "server[type=\"armeria\"].accessLogWriter");
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
        this.printBanner(environment.getName());
        final MetricRegistry metrics = environment.metrics();
        ThreadPool threadPool = this.createThreadPool(metrics);
        Server server = this.buildServer(environment.lifecycle(), threadPool);

        final JerseyEnvironment jersey = environment.jersey();
        if (!isJerseyEnabled()) {
            jersey.disable();
        }

        Handler applicationHandler = this.createAppServlet(server,
                jersey,
                environment.getObjectMapper(),
                environment.getValidator(),
                environment.getApplicationContext(),
                environment.getJerseyServletContainer(),
                metrics);
        Handler adminHandler = this.createAdminServlet(server,
                environment.getAdminContext(),
                metrics,
                environment.healthChecks());
        ContextRoutingHandler routingHandler = new ContextRoutingHandler(ImmutableMap.of(
                getApplicationContextPath(), applicationHandler,
                getAdminContextPath(), adminHandler));
        Handler gzipHandler = this.buildGzipHandler(routingHandler);
        server.setHandler(this.addStatsHandler(this.addRequestLog(server, gzipHandler, environment.getName())));

        setupArmeria(server, metrics);

        return server;
    }

    private void setupArmeria(final Server server, final MetricRegistry metricRegistry) {
        LOGGER.debug("Building Armeria Server");
        final JettyService jettyService = JettyService.forServer(server);
        this.serverBuilder = com.linecorp.armeria.server.Server.builder()
                .meterRegistry(DropwizardMeterRegistries.newRegistry(metricRegistry))
                .serviceUnder("/", jettyService);

        if (this.connector != null) {
            try {
                setupAmeriaHttp(this.serverBuilder, this.connector);
            } catch (SSLException | CertificateException e) {
                LOGGER.error("Unable to define TLS Server", e);
            }
        }

        // TODO: Add more items to server builder via Configuration
        // https://line.github.io/armeria/advanced-production-checklist.html
        serverBuilder.maxNumConnections(getMaxQueuedRequests());
        serverBuilder.blockingTaskExecutor(Executors.newFixedThreadPool(getMaxThreads()), true);
        serverBuilder.maxRequestLength(this.maxRequestLength.toBytes());
        serverBuilder.idleTimeoutMillis(getIdleThreadTimeout().toMilliseconds());
        serverBuilder.gracefulShutdownTimeout(
                Duration.ofSeconds(30L),
                Duration.ofMillis(getShutdownGracePeriod().toMilliseconds()));
    }

    private void setupAmeriaHttp(final ServerBuilder sb, final ConnectorFactory connector) throws SSLException, CertificateException {
        if (connector instanceof ArmeriaHttpConnectorFactory) {
            LOGGER.debug("Building Armeria HTTP Server");
            ArmeriaHttpConnectorFactory factory = (ArmeriaHttpConnectorFactory) connector;
            sb.http(factory.getPort());
            // TODO: More HTTP1 settings
            sb.http1MaxHeaderSize((int) factory.getMaxRequestHeaderSize().toBytes());
        } else if (connector instanceof ArmeriaHttpsConnectorFactory) {
            LOGGER.debug("Building Armeria HTTPS Server");
            ArmeriaHttpsConnectorFactory factory = (ArmeriaHttpsConnectorFactory) connector;
            sb.https(factory.getPort());
            if (factory.isSelfSigned()) {
                sb.tlsSelfSigned();
            }
            File cert = new File(factory.getKeyCertChainFile());
            File keyStore = new File(factory.getKeyStorePath());
            if (factory.isValidKeyStorePassword()) {
                sb.tls(cert, keyStore, factory.getKeyStorePassword());
            } else {
                sb.tls(cert, keyStore);
            }
            // TODO: More HTTPS settings
        } else {
            // TODO: HTTP isn't required for just gRPC servers. Is a null connector representative of that?
            throw new ClassCastException("server.connector must be one of types "
                    + ArmeriaHttpConnectorFactory.TYPE + " or " + ArmeriaHttpsConnectorFactory.TYPE);
        }

        if (getAccessLogWriter() != null) {
            LOGGER.trace("Setting up Armeria AccessLogWriter");
            sb.accessLogWriter(getAccessLogWriter().getWriter(), true);
        }
    }

}
