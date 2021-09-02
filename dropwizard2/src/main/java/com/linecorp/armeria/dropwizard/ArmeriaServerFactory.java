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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.validation.Valid;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.hibernate.validator.constraints.NotEmpty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonTypeName;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.DropwizardMeterRegistries;
import com.linecorp.armeria.common.util.ThreadFactories;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.jetty.JettyService;

import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.jetty.ContextRoutingHandler;
import io.dropwizard.server.AbstractServerFactory;
import io.dropwizard.server.ServerFactory;
import io.dropwizard.setup.Environment;

/**
 * A Dropwizard {@link ServerFactory} implementation for Armeria that replaces
 * Dropwizard's default Jetty handler with one provided by Armeria.
 */
@JsonTypeName(ArmeriaServerFactory.TYPE)
class ArmeriaServerFactory extends AbstractServerFactory {

    public static final String TYPE = "armeria";
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaServerFactory.class);

    @JsonUnwrapped
    @Nullable
    private @Valid ArmeriaSettings armeriaSettings;

    @JsonIgnore
    @Nullable
    private transient ServerBuilder serverBuilder;

    @SuppressWarnings("deprecation")
    @NotEmpty
    private String applicationContextPath = "/application";
    @SuppressWarnings("deprecation")
    @NotEmpty
    private String adminContextPath = "/admin";
    @JsonProperty
    private boolean jerseyEnabled = true;

    @JsonIgnore
    public ServerBuilder getServerBuilder() {
        checkState(serverBuilder != null, "serverBuilder is null.");
        return serverBuilder;
    }

    @Override
    public Server build(Environment environment) {
        requireNonNull(environment, "environment");
        printBanner(environment.getName());
        final MetricRegistry metrics = environment.metrics();
        final ThreadPool threadPool = createThreadPool(metrics);
        final Server server = buildServer(environment.lifecycle(), threadPool);

        final JerseyEnvironment jersey = environment.jersey();
        if (!isJerseyEnabled()) {
            jersey.disable();
        }

        addDefaultHandlers(server, environment, metrics);
        serverBuilder = buildServerBuilder(server, metrics);
        return server;
    }

    @Override
    public void configure(Environment environment) {
        logger.info("Registering jersey handler with root path prefix: {}", applicationContextPath);
        environment.getApplicationContext().setContextPath(applicationContextPath);

        logger.info("Registering admin handler with root path prefix: {}", adminContextPath);
        environment.getAdminContext().setContextPath(adminContextPath);
    }

    private void addDefaultHandlers(Server server, Environment environment, MetricRegistry metrics) {
        final JerseyEnvironment jersey = environment.jersey();
        final Handler applicationHandler = createAppServlet(
                server,
                jersey,
                environment.getObjectMapper(),
                environment.getValidator(),
                environment.getApplicationContext(),
                environment.getJerseyServletContainer(),
                metrics);
        final Handler adminHandler = createAdminServlet(server, environment.getAdminContext(),
                                                        metrics, environment.healthChecks());
        final ContextRoutingHandler routingHandler = new ContextRoutingHandler(
                ImmutableMap.of(applicationContextPath, applicationHandler, adminContextPath, adminHandler));
        final Handler gzipHandler = buildGzipHandler(routingHandler);
        server.setHandler(addStatsHandler(addRequestLog(server, gzipHandler, environment.getName())));
    }

    private ServerBuilder buildServerBuilder(Server server, MetricRegistry metricRegistry) {
        final ServerBuilder serverBuilder = com.linecorp.armeria.server.Server.builder();
        serverBuilder.meterRegistry(DropwizardMeterRegistries.newRegistry(metricRegistry));

        if (armeriaSettings != null) {
            ArmeriaConfigurationUtil.configureServer(serverBuilder, armeriaSettings);
        } else {
            logger.warn("Armeria configuration was null. ServerBuilder is not customized from it.");
        }

        return serverBuilder.blockingTaskExecutor(newBlockingTaskExecutor(), true)
                            .serviceUnder("/", JettyService.of(server));
    }

    private ScheduledThreadPoolExecutor newBlockingTaskExecutor() {
        final ScheduledThreadPoolExecutor blockingTaskExecutor = new ScheduledThreadPoolExecutor(
                getMaxThreads(),
                ThreadFactories.newThreadFactory("armeria-dropwizard-blocking-tasks", true));
        blockingTaskExecutor.setKeepAliveTime(60, TimeUnit.SECONDS);
        blockingTaskExecutor.allowCoreThreadTimeOut(true);
        return blockingTaskExecutor;
    }

    ArmeriaSettings getArmeriaSettings() {
        return armeriaSettings;
    }

    void setArmeriaSettings(ArmeriaSettings armeriaSettings) {
        this.armeriaSettings = armeriaSettings;
    }

    String getApplicationContextPath() {
        return applicationContextPath;
    }

    void setApplicationContextPath(String applicationContextPath) {
        this.applicationContextPath = applicationContextPath;
    }

    String getAdminContextPath() {
        return adminContextPath;
    }

    void setAdminContextPath(String adminContextPath) {
        this.adminContextPath = adminContextPath;
    }

    public boolean isJerseyEnabled() {
        return jerseyEnabled;
    }

    public void setJerseyEnabled(boolean jerseyEnabled) {
        this.jerseyEnabled = jerseyEnabled;
    }
}
