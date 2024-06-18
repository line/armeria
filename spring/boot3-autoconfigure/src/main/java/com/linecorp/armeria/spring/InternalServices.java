/*
 * Copyright 2021 LINE Corporation
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

import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.maybeNewPort;

import java.net.InetAddress;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.common.util.PortUtil;
import com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.docs.DocService;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.server.healthcheck.HealthCheckServiceBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * A collection of internal {@code HttpService}s and their {@code Port}s.
 */
@UnstableApi
public final class InternalServices {

    private static final Logger logger = LoggerFactory.getLogger(InternalServices.class);

    private static boolean hasAllClasses(String... classNames) {
        for (String className : classNames) {
            try {
                Class.forName(className, false, ArmeriaConfigurationUtil.class.getClassLoader());
            } catch (Throwable t) {
                return false;
            }
        }
        return true;
    }

    static {
        // InternalServices is the only class that both boot-starter and boot-webflux-starter always depend on.

        // Disable the default shutdown hook to gracefully close the client factory after the server is
        // shut down.
        ClientFactory.disableShutdownHook();
        // The shutdown hooks are invoked after all other contexts are closed.
        // The server is closed by ConfigurableApplicationContext.closeAndWait().
        // https://github.com/spring-projects/spring-boot/blame/781d7b0394c71e20f098f64a3261a18346ccd915/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/SpringApplicationShutdownHook.java#L114-L116
        SpringApplication.getShutdownHandlers().add(ClientFactory::closeDefault);
    }

    /**
     * Returns a newly created {@link InternalServices} from the specified properties.
     */
    @UnstableApi
    public static InternalServices of(
            ArmeriaSettings settings,
            MeterRegistry meterRegistry,
            List<HealthChecker> healthCheckers,
            List<HealthCheckServiceConfigurator> healthCheckServiceConfigurators,
            List<DocServiceConfigurator> docServiceConfigurators,
            @Nullable Integer managementServerPort,
            @Nullable InetAddress managementServerAddress,
            boolean enableManagementServerSsl) {

        DocService docService = null;
        if (!Strings.isNullOrEmpty(settings.getDocsPath())) {
            final DocServiceBuilder docServiceBuilder = DocService.builder();
            docServiceConfigurators.forEach(configurator -> configurator.configure(docServiceBuilder));
            docService = docServiceBuilder.build();
        }

        HealthCheckService healthCheckService = null;
        if (!Strings.isNullOrEmpty(settings.getHealthCheckPath())) {
            final HealthCheckServiceBuilder builder = HealthCheckService.builder().checkers(healthCheckers);
            healthCheckServiceConfigurators.forEach(configurator -> configurator.configure(builder));
            healthCheckService = builder.build();
        } else if (!healthCheckServiceConfigurators.isEmpty()) {
            logger.warn("{}s exist but they are disabled by the empty 'health-check-path' property." +
                        " configurators: {}",
                        HealthCheckServiceConfigurator.class.getSimpleName(),
                        healthCheckServiceConfigurators);
        }

        HttpService expositionService = null;
        if (settings.isEnableMetrics() && !Strings.isNullOrEmpty(settings.getMetricsPath())) {
            final String prometheusMeterRegistryClassName =
                    "io.micrometer.prometheusmetrics.PrometheusMeterRegistry";
            final boolean hasPrometheus = hasAllClasses(
                    prometheusMeterRegistryClassName,
                    "io.prometheus.metrics.model.registry.PrometheusRegistry",
                    "com.linecorp.armeria.server.prometheus.PrometheusExpositionService");

            if (hasPrometheus) {
                expositionService = PrometheusSupport.newExpositionService(meterRegistry);
            }

            final String legacyPrometheusMeterRegistryClassName =
                    "io.micrometer.prometheus.PrometheusMeterRegistry";
            final boolean hasLegacyPrometheus = hasAllClasses(
                    legacyPrometheusMeterRegistryClassName,
                    "io.prometheus.client.CollectorRegistry");

            if (hasLegacyPrometheus) {
                expositionService = PrometheusLegacySupport.newExpositionService(meterRegistry);
            }

            final String dropwizardMeterRegistryClassName =
                    "io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry";
            if (expositionService == null) {
                final boolean hasDropwizard = hasAllClasses(
                        dropwizardMeterRegistryClassName,
                        "com.codahale.metrics.MetricRegistry",
                        "com.codahale.metrics.json.MetricsModule");
                if (hasDropwizard) {
                    expositionService = DropwizardSupport.newExpositionService(meterRegistry);
                }
            }
            if (expositionService == null) {
                logger.debug("Failed to expose metrics to '{}' with {} (expected: either {} or {})",
                             settings.getMetricsPath(), meterRegistry, legacyPrometheusMeterRegistryClassName,
                             dropwizardMeterRegistryClassName);
            }
        }

        final Port internalPort = settings.getInternalServices();
        if (internalPort != null && internalPort.getPort() == 0) {
            internalPort.setPort(PortUtil.unusedTcpPort());
        }
        return new InternalServices(docService, expositionService,
                                    healthCheckService, internalPort,
                                    maybeNewPort(managementServerPort,
                                                 managementServerAddress,
                                                 enableManagementServerSsl));
    }

    @Nullable
    private final DocService docService;
    @Nullable
    private final HttpService metricsExpositionService;
    @Nullable
    private final HealthCheckService healthCheckService;

    @Nullable
    private final Port internalServicePort;
    @Nullable
    private final Port managementServerPort;

    private InternalServices(
            @Nullable DocService docService,
            @Nullable HttpService metricsExpositionService,
            @Nullable HealthCheckService healthCheckService,
            @Nullable Port internalServicePort,
            @Nullable Port managementServerPort) {
        this.healthCheckService = healthCheckService;
        this.metricsExpositionService = metricsExpositionService;
        this.docService = docService;
        this.internalServicePort = internalServicePort;
        this.managementServerPort = managementServerPort;
    }

    /**
     * Returns the {@link DocService}.
     */
    @Nullable
    public DocService docService() {
        return docService;
    }

    /**
     * Returns the metrics exposition {@link HttpService}.
     */
    @Nullable
    public HttpService metricsExpositionService() {
        return metricsExpositionService;
    }

    /**
     * Returns the {@link HealthCheckService}.
     */
    @Nullable
    public HealthCheckService healthCheckService() {
        return healthCheckService;
    }

    /**
     * Returns the port to serve the internal services on.
     */
    @Nullable
    public Port internalServicePort() {
        return internalServicePort;
    }

    /**
     * Returns the management server port of
     * {@code org.springframework.boot.actuate.autoconfigure.web.server.ManagementServerProperties}.
     */
    @Nullable
    public Port managementServerPort() {
        return managementServerPort;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("docService", docService)
                          .add("metricsExpositionService", metricsExpositionService)
                          .add("healthCheckService", healthCheckService)
                          .add("internalServicePort", internalServicePort)
                          .add("managementServerPort", managementServerPort)
                          .toString();
    }
}
