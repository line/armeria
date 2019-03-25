/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.spring;

import static com.google.common.base.MoreObjects.firstNonNull;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.apache.thrift.TBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ResourceUtils;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.json.MetricsModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.spring.AbstractServiceRegistrationBean;
import com.linecorp.armeria.spring.AnnotatedServiceRegistrationBean;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.HttpServiceRegistrationBean;
import com.linecorp.armeria.spring.MeterIdPrefixFunctionFactory;
import com.linecorp.armeria.spring.Ssl;
import com.linecorp.armeria.spring.ThriftServiceRegistrationBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.NetUtil;
import io.prometheus.client.CollectorRegistry;

/**
 * A utility class which is used to configure a {@link ServerBuilder} with the {@link ArmeriaSettings} and
 * service registration beans.
 */
public final class ArmeriaConfigurationUtil {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaConfigurationUtil.class);

    private static final HealthChecker[] EMPTY_HEALTH_CHECKERS = new HealthChecker[0];

    private static final String METER_TYPE = "server";

    /**
     * Sets graceful shutdown timeout, health check services and {@link MeterRegistry} for the specified
     * {@link ServerBuilder}.
     */
    public static void configureServerWithArmeriaSettings(ServerBuilder server, ArmeriaSettings settings,
                                                          MeterRegistry meterRegistry,
                                                          List<HealthChecker> healthCheckers) {
        requireNonNull(server, "server");
        requireNonNull(settings, "settings");
        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(healthCheckers, "healthCheckers");

        if (settings.getGracefulShutdownQuietPeriodMillis() >= 0 &&
            settings.getGracefulShutdownTimeoutMillis() >= 0) {
            server.gracefulShutdownTimeout(settings.getGracefulShutdownQuietPeriodMillis(),
                                           settings.getGracefulShutdownTimeoutMillis());
            logger.debug("Set graceful shutdown timeout: quiet period {} ms, timeout {} ms",
                         settings.getGracefulShutdownQuietPeriodMillis(),
                         settings.getGracefulShutdownTimeoutMillis());
        }

        final String healthCheckPath = settings.getHealthCheckPath();
        if (!Strings.isNullOrEmpty(healthCheckPath)) {
            server.service(healthCheckPath,
                           new HttpHealthCheckService(healthCheckers.toArray(EMPTY_HEALTH_CHECKERS)));
        }

        server.meterRegistry(meterRegistry);

        if (settings.isEnableMetrics() && !Strings.isNullOrEmpty(settings.getMetricsPath())) {
            if (meterRegistry instanceof CompositeMeterRegistry) {
                final Set<MeterRegistry> childRegistries =
                        ((CompositeMeterRegistry) meterRegistry).getRegistries();
                childRegistries.stream()
                               .filter(PrometheusMeterRegistry.class::isInstance)
                               .map(PrometheusMeterRegistry.class::cast)
                               .findAny()
                               .ifPresent(r -> addPrometheusExposition(settings, server, r));
            } else if (meterRegistry instanceof PrometheusMeterRegistry) {
                addPrometheusExposition(settings, server, (PrometheusMeterRegistry) meterRegistry);
            } else if (meterRegistry instanceof DropwizardMeterRegistry) {
                final MetricRegistry dropwizardRegistry =
                        ((DropwizardMeterRegistry) meterRegistry).getDropwizardRegistry();
                final ObjectMapper objectMapper = new ObjectMapper()
                        .enable(SerializationFeature.INDENT_OUTPUT)
                        .registerModule(new MetricsModule(TimeUnit.SECONDS, TimeUnit.MILLISECONDS, true));
                server.service(
                        settings.getMetricsPath(),
                        new AbstractHttpService() {
                            @Override
                            protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                                    throws Exception {
                                return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8,
                                                       objectMapper.writeValueAsBytes(dropwizardRegistry));
                            }
                        });
            }
        }

        if (settings.getSsl() != null) {
            configureTls(server, settings.getSsl());
        }
    }

    /**
     * Adds {@link Port}s to the specified {@link ServerBuilder}.
     */
    public static void configurePorts(ServerBuilder server, List<Port> ports) {
        requireNonNull(server, "server");
        requireNonNull(ports, "ports");
        ports.forEach(p -> {
            final String ip = p.getIp();
            final String iface = p.getIface();
            final int port = p.getPort();
            final List<SessionProtocol> protocols = p.getProtocols();

            if (ip == null) {
                if (iface == null) {
                    server.port(new ServerPort(port, protocols));
                } else {
                    try {
                        final Enumeration<InetAddress> e = NetworkInterface.getByName(iface).getInetAddresses();
                        while (e.hasMoreElements()) {
                            server.port(new ServerPort(new InetSocketAddress(e.nextElement(), port),
                                                       protocols));
                        }
                    } catch (SocketException e) {
                        throw new IllegalStateException("Failed to find an iface: " + iface, e);
                    }
                }
            } else if (iface == null) {
                if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                    final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
                    try {
                        server.port(new ServerPort(new InetSocketAddress(
                                InetAddress.getByAddress(bytes), port), protocols));
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
        });
    }

    /**
     * Adds Thrift services to the specified {@link ServerBuilder}.
     */
    public static void configureThriftServices(
            ServerBuilder server, List<ThriftServiceRegistrationBean> beans,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory,
            @Nullable String docsPath) {
        requireNonNull(server, "server");
        requireNonNull(beans, "beans");

        final List<TBase<?, ?>> docServiceRequests = new ArrayList<>();
        final Map<String, Collection<HttpHeaders>> docServiceHeaders = new HashMap<>();
        beans.forEach(bean -> {
            Service<HttpRequest, HttpResponse> service = bean.getService();
            for (Function<Service<HttpRequest, HttpResponse>, ? extends Service<HttpRequest, HttpResponse>>
                    decorator : bean.getDecorators()) {
                service = service.decorate(decorator);
            }
            service = setupMetricCollectingService(service, bean, meterIdPrefixFunctionFactory);
            server.service(bean.getPath(), service);
            docServiceRequests.addAll(bean.getExampleRequests());
            ThriftServiceUtils.serviceNames(bean.getService())
                              .forEach(serviceName -> docServiceHeaders
                                      .put(serviceName,
                                           bean.getExampleHeaders()));
        });

        if (!Strings.isNullOrEmpty(docsPath)) {
            final DocServiceBuilder docServiceBuilder = new DocServiceBuilder();
            docServiceBuilder.exampleRequest(docServiceRequests);
            for (Entry<String, Collection<HttpHeaders>> entry : docServiceHeaders.entrySet()) {
                docServiceBuilder.exampleHttpHeaders(entry.getKey(), entry.getValue());
            }
            server.serviceUnder(docsPath, docServiceBuilder.build());
        }
    }

    /**
     * Adds HTTP services to the specified {@link ServerBuilder}.
     */
    public static void configureHttpServices(
            ServerBuilder server, List<HttpServiceRegistrationBean> beans,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory) {
        requireNonNull(server, "server");
        requireNonNull(beans, "beans");

        beans.forEach(bean -> {
            Service<HttpRequest, HttpResponse> service = bean.getService();
            for (Function<Service<HttpRequest, HttpResponse>, ? extends Service<HttpRequest, HttpResponse>>
                    decorator : bean.getDecorators()) {
                service = service.decorate(decorator);
            }
            service = setupMetricCollectingService(service, bean, meterIdPrefixFunctionFactory);
            server.service(bean.getPathMapping(), service);
        });
    }

    /**
     * Adds annotated HTTP services to the specified {@link ServerBuilder}.
     */
    public static void configureAnnotatedHttpServices(
            ServerBuilder server, List<AnnotatedServiceRegistrationBean> beans,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory) {
        requireNonNull(server, "server");
        requireNonNull(beans, "beans");

        beans.forEach(bean -> {
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator = Function.identity();
            for (Function<Service<HttpRequest, HttpResponse>, ? extends Service<HttpRequest, HttpResponse>>
                    d : bean.getDecorators()) {
                decorator = decorator.andThen(d);
            }
            if (meterIdPrefixFunctionFactory != null) {
                decorator = decorator.andThen(
                        metricCollectingServiceDecorator(bean, meterIdPrefixFunctionFactory));
            }
            final ImmutableList<Object> exceptionHandlersAndConverters =
                    ImmutableList.builder()
                                 .addAll(bean.getExceptionHandlers())
                                 .addAll(bean.getRequestConverters())
                                 .addAll(bean.getResponseConverters())
                                 .build();
            server.annotatedService(bean.getPathPrefix(), bean.getService(), decorator,
                                    exceptionHandlersAndConverters);
        });
    }

    private static Service<HttpRequest, HttpResponse> setupMetricCollectingService(
            Service<HttpRequest, HttpResponse> service,
            AbstractServiceRegistrationBean<?, ?> bean,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory) {
        requireNonNull(service, "service");
        requireNonNull(bean, "bean");

        if (meterIdPrefixFunctionFactory == null) {
            return service;
        }
        return service.decorate(metricCollectingServiceDecorator(bean, meterIdPrefixFunctionFactory));
    }

    private static Function<Service<HttpRequest, HttpResponse>,
            MetricCollectingService<HttpRequest, HttpResponse>> metricCollectingServiceDecorator(
            AbstractServiceRegistrationBean<?, ?> bean,
            MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory) {
        requireNonNull(bean, "bean");
        requireNonNull(meterIdPrefixFunctionFactory, "meterIdPrefixFunctionFactory");

        return MetricCollectingService.newDecorator(
                meterIdPrefixFunctionFactory.get(METER_TYPE, bean.getServiceName()));
    }

    private static void addPrometheusExposition(ArmeriaSettings armeriaSettings, ServerBuilder server,
                                                PrometheusMeterRegistry registry) {
        requireNonNull(armeriaSettings, "armeriaSettings");
        requireNonNull(server, "server");
        requireNonNull(registry, "registry");

        final String metricsPath = armeriaSettings.getMetricsPath();
        if (metricsPath == null) {
            return;
        }

        final CollectorRegistry prometheusRegistry = registry.getPrometheusRegistry();
        server.service(metricsPath, new PrometheusExpositionService(prometheusRegistry));
    }

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    public static void configureTls(ServerBuilder sb, Ssl ssl) {
        configureTls(sb, ssl, null, null);
    }

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    public static void configureTls(ServerBuilder sb, Ssl ssl,
                                    @Nullable Supplier<KeyStore> keyStoreSupplier,
                                    @Nullable Supplier<KeyStore> trustStoreSupplier) {
        if (!ssl.isEnabled()) {
            return;
        }
        try {
            if (keyStoreSupplier == null && trustStoreSupplier == null &&
                ssl.getKeyStore() == null && ssl.getTrustStore() == null) {
                logger.warn("Configuring TLS with a self-signed certificate " +
                            "because no key or trust store was specified");
                sb.tlsSelfSigned();
                return;
            }

            final SslContextBuilder sslBuilder = SslContextBuilder
                    .forServer(getKeyManagerFactory(ssl, keyStoreSupplier))
                    .trustManager(getTrustManagerFactory(ssl, trustStoreSupplier));

            final List<String> enabledProtocols = ssl.getEnabledProtocols();
            if (enabledProtocols != null) {
                sslBuilder.protocols(enabledProtocols.toArray(new String[enabledProtocols.size()]));
            }

            final List<String> ciphers = ssl.getCiphers();
            if (ciphers != null) {
                sslBuilder.ciphers(ImmutableList.copyOf(ciphers));
            }

            final ClientAuth clientAuth = ssl.getClientAuth();
            if (clientAuth != null) {
                sslBuilder.clientAuth(clientAuth);
            }

            sb.tls(sslBuilder.build());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to configure TLS: " + e, e);
        }
    }

    private static KeyManagerFactory getKeyManagerFactory(
            Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
        final KeyStore store;
        if (sslStoreProvider != null) {
            store = sslStoreProvider.get();
        } else {
            store = loadKeyStore(ssl.getKeyStoreType(), ssl.getKeyStore(), ssl.getKeyStorePassword());
        }

        final KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());

        String keyPassword = ssl.getKeyPassword();
        if (keyPassword == null) {
            keyPassword = ssl.getKeyStorePassword();
        }

        keyManagerFactory.init(store, keyPassword != null ? keyPassword.toCharArray()
                                                          : null);
        return keyManagerFactory;
    }

    private static TrustManagerFactory getTrustManagerFactory(
            Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
        final KeyStore store;
        if (sslStoreProvider != null) {
            store = sslStoreProvider.get();
        } else {
            store = loadKeyStore(ssl.getTrustStoreType(), ssl.getTrustStore(), ssl.getTrustStorePassword());
        }

        final TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(store);
        return trustManagerFactory;
    }

    @Nullable
    private static KeyStore loadKeyStore(
            @Nullable String type,
            @Nullable String resource,
            @Nullable String password) throws IOException, GeneralSecurityException {
        if (resource == null) {
            return null;
        }
        final KeyStore store = KeyStore.getInstance(firstNonNull(type, "JKS"));
        final URL url = ResourceUtils.getURL(resource);
        store.load(url.openStream(), password != null ? password.toCharArray()
                                                      : null);
        return store;
    }

    private ArmeriaConfigurationUtil() {}
}
