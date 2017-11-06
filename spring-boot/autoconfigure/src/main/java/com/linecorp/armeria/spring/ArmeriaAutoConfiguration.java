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
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.apache.thrift.TBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.util.NetUtil;
import io.prometheus.client.CollectorRegistry;

@Configuration
@EnableConfigurationProperties(ArmeriaSettings.class)
@ConditionalOnMissingBean(Server.class)
public class ArmeriaAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaAutoConfiguration.class);

    private static final HealthChecker[] EMPTY_HEALTH_CHECKERS = new HealthChecker[0];

    private static final Port DEFAULT_PORT = new Port().setPort(8080)
                                                       .setProtocol(SessionProtocol.HTTP);

    private static final String METER_TYPE = "server";

    /**
     * Create a {@link Server} bean.
     */
    @Bean
    public Server armeriaServer(
            ArmeriaSettings armeriaSettings,
            Optional<MeterRegistry> meterRegistry,
            Optional<MeterIdPrefixFunctionFactory> meterIdPrefixFunctionFactory,
            Optional<List<HealthChecker>> healthCheckers,
            Optional<List<ArmeriaServerConfigurator>> armeriaServiceInitializers,
            Optional<List<ThriftServiceRegistrationBean>> thriftServiceRegistrationBeans,
            Optional<List<HttpServiceRegistrationBean>> httpServiceRegistrationBeans,
            Optional<List<AnnotatedServiceRegistrationBean>> annotatedServiceRegistrationBeans)
            throws InterruptedException {

        if (!armeriaServiceInitializers.isPresent() &&
            !thriftServiceRegistrationBeans.isPresent() &&
            !httpServiceRegistrationBeans.isPresent() &&
            !annotatedServiceRegistrationBeans.isPresent()) {
            // No services to register, no need to start up armeria server.
            return null;
        }

        final boolean metricsEnabled = armeriaSettings.isEnableMetrics();
        final MeterIdPrefixFunctionFactory meterIdPrefixFuncFactory =
                meterIdPrefixFunctionFactory.orElse(MeterIdPrefixFunctionFactory.DEFAULT);

        final ServerBuilder server = new ServerBuilder();
        meterRegistry.ifPresent(server::meterRegistry);

        if (armeriaSettings.getGracefulShutdownQuietPeriodMillis() != -1 &&
            armeriaSettings.getGracefulShutdownTimeoutMillis() != -1) {
            server.gracefulShutdownTimeout(
                    armeriaSettings.getGracefulShutdownQuietPeriodMillis(),
                    armeriaSettings.getGracefulShutdownTimeoutMillis());
        }

        configurePorts(armeriaSettings, server);

        List<TBase<?, ?>> docServiceRequests = new ArrayList<>();
        Map<String, Collection<HttpHeaders>> docServiceHeaders = new HashMap<>();
        thriftServiceRegistrationBeans.ifPresent(beans -> beans.forEach(bean -> {
            Service<HttpRequest, HttpResponse> service = bean.getService().decorate(bean.getDecorator());
            if (metricsEnabled) {
                service = service.decorate(MetricCollectingService.newDecorator(
                        meterIdPrefixFuncFactory.get(METER_TYPE, bean.getServiceName())));
            }

            server.service(bean.getPath(), service);
            docServiceRequests.addAll(bean.getExampleRequests());
            bean.getService().as(THttpService.class).ifPresent(
                    beanService -> beanService.entries().forEach((serviceName, entry) -> {
                        for (Class<?> iface : entry.interfaces()) {
                            docServiceHeaders.put(iface.getEnclosingClass().getName(),
                                                  bean.getExampleHeaders());
                        }
                    }));
        }));

        httpServiceRegistrationBeans.ifPresent(beans -> beans.forEach(bean -> {
            Service<HttpRequest, HttpResponse> service = bean.getService().decorate(bean.getDecorator());
            if (metricsEnabled) {
                service = service.decorate(MetricCollectingService.newDecorator(
                        meterIdPrefixFuncFactory.get(METER_TYPE, bean.getServiceName())));
            }
            server.service(bean.getPathMapping(), service);
        }));

        annotatedServiceRegistrationBeans.ifPresent(beans -> beans.forEach(bean -> {
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<HttpRequest, HttpResponse>> decorator = bean.getDecorator();
            if (metricsEnabled) {
                decorator = decorator.andThen(MetricCollectingService.newDecorator(
                        meterIdPrefixFuncFactory.get(METER_TYPE, bean.getServiceName())));
            }
            server.annotatedService(bean.getPathPrefix(), bean.getService(), decorator);
        }));

        if (!Strings.isNullOrEmpty(armeriaSettings.getHealthCheckPath())) {
            server.service(armeriaSettings.getHealthCheckPath(),
                           new HttpHealthCheckService(healthCheckers.orElseGet(Collections::emptyList)
                                                                    .toArray(EMPTY_HEALTH_CHECKERS)));
        }

        if (!Strings.isNullOrEmpty(armeriaSettings.getDocsPath())) {
            DocServiceBuilder docServiceBuilder = new DocServiceBuilder();
            docServiceBuilder.exampleRequest(docServiceRequests);
            for (Entry<String, Collection<HttpHeaders>> entry : docServiceHeaders.entrySet()) {
                docServiceBuilder.exampleHttpHeaders(entry.getKey(), entry.getValue());
            }
            server.serviceUnder(armeriaSettings.getDocsPath(), docServiceBuilder.build());
        }

        if (metricsEnabled && !Strings.isNullOrEmpty(armeriaSettings.getMetricsPath())) {
            final MeterRegistry registry = meterRegistry.orElse(Metrics.globalRegistry);
            if (registry instanceof CompositeMeterRegistry) {
                final Set<MeterRegistry> childRegistries = ((CompositeMeterRegistry) registry).getRegistries();
                childRegistries.stream()
                               .filter(PrometheusMeterRegistry.class::isInstance)
                               .map(PrometheusMeterRegistry.class::cast)
                               .findAny()
                               .ifPresent(r -> addPrometheusExposition(armeriaSettings, server, r));
            } else if (registry instanceof PrometheusMeterRegistry) {
                addPrometheusExposition(armeriaSettings, server, (PrometheusMeterRegistry) registry);
            } else if (registry instanceof DropwizardMeterRegistry) {
                final MetricRegistry dropwizardRegistry =
                        ((DropwizardMeterRegistry) registry).getDropwizardRegistry();
                final ObjectMapper objectMapper = new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true));
                server.service(
                        armeriaSettings.getMetricsPath(),
                        new AbstractHttpService() {
                            @Override
                            protected void doGet(ServiceRequestContext ctx, HttpRequest req,
                                                 HttpResponseWriter res) throws Exception {
                                res.respond(HttpStatus.OK, MediaType.JSON_UTF_8,
                                            objectMapper.writeValueAsBytes(dropwizardRegistry));
                            }
                        });
            }
        }

        armeriaServiceInitializers.ifPresent(
                initializers -> initializers.forEach(
                        initializer -> initializer.configure(server)));

        Server s = server.build();

        s.start().join();
        logger.info("Armeria server started at ports: {}", s.activePorts());
        return s;
    }

    private static void addPrometheusExposition(ArmeriaSettings armeriaSettings, ServerBuilder server,
                                                PrometheusMeterRegistry registry) {
        final CollectorRegistry prometheusRegistry =
                registry.getPrometheusRegistry();
        server.service(armeriaSettings.getMetricsPath(),
                       new PrometheusExpositionService(prometheusRegistry));
    }

    private static void configurePorts(ArmeriaSettings armeriaSettings, ServerBuilder server) {
        if (armeriaSettings.getPorts().isEmpty()) {
            server.port(DEFAULT_PORT.getPort(), DEFAULT_PORT.getProtocol());
            return;
        }

        for (Port p : armeriaSettings.getPorts()) {
            final String ip = p.getIp();
            final String iface = p.getIface();
            final int port = p.getPort();
            final SessionProtocol proto = p.getProtocol();

            if (ip == null) {
                if (iface == null) {
                    server.port(new InetSocketAddress(port), proto);
                } else {
                    try {
                        Enumeration<InetAddress> e = NetworkInterface.getByName(iface).getInetAddresses();
                        while (e.hasMoreElements()) {
                            server.port(new InetSocketAddress(e.nextElement(), port), proto);
                        }
                    } catch (SocketException e) {
                        throw new IllegalStateException("Failed to find an iface: " + iface, e);
                    }
                }
            } else if (iface == null) {
                if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                    final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
                    try {
                        server.port(new InetSocketAddress(InetAddress.getByAddress(bytes), port), proto);
                    } catch (UnknownHostException e) {
                        // Should never happen.
                        throw new Error(e);
                    }
                } else {
                    throw new IllegalStateException("invalid IP address: " + ip);
                }
            } else {
                throw new IllegalStateException("A port cannot have both IP and iface: " + p);
            }
        }
    }
}
