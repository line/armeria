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
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
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
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.HttpHeaderNames;
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
import com.linecorp.armeria.server.ServiceWithRoutes;
import com.linecorp.armeria.server.docs.DocServiceBuilder;
import com.linecorp.armeria.server.encoding.HttpEncodingService;
import com.linecorp.armeria.server.healthcheck.HealthChecker;
import com.linecorp.armeria.server.healthcheck.HttpHealthCheckService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.PrometheusExpositionService;
import com.linecorp.armeria.spring.AbstractServiceRegistrationBean;
import com.linecorp.armeria.spring.AnnotatedServiceRegistrationBean;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.GrpcServiceRegistrationBean;
import com.linecorp.armeria.spring.GrpcServiceRegistrationBean.ExampleRequest;
import com.linecorp.armeria.spring.HttpServiceRegistrationBean;
import com.linecorp.armeria.spring.MeterIdPrefixFunctionFactory;
import com.linecorp.armeria.spring.Ssl;
import com.linecorp.armeria.spring.ThriftServiceRegistrationBean;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import io.micrometer.core.instrument.dropwizard.DropwizardMeterRegistry;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.NetUtil;
import io.prometheus.client.CollectorRegistry;

/**
 * A utility class which is used to configure a {@link ServerBuilder} with the {@link ArmeriaSettings} and
 * service registration beans.
 */
public final class ArmeriaConfigurationUtil {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaConfigurationUtil.class);

    private static final HealthChecker[] EMPTY_HEALTH_CHECKERS = new HealthChecker[0];
    private static final String[] EMPTY_PROTOCOL_NAMES = new String[0];

    private static final String METER_TYPE = "server";

    /**
     * The pattern for data size text.
     */
    private static final Pattern DATA_SIZE_PATTERN = Pattern.compile("^([+]?\\d+)([a-zA-Z]{0,2})$");

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

        final ArmeriaSettings.Compression compression = settings.getCompression();
        if (compression != null && compression.isEnabled()) {
            server.decorator(contentEncodingDecorator(compression.getMimeTypes(),
                                                      compression.getExcludedUserAgents(),
                                                      parseDataSize(compression.getMinResponseSize())));
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
            final List<SessionProtocol> protocols = firstNonNull(p.getProtocols(),
                                                                 ImmutableList.of(SessionProtocol.HTTP));

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
            ServerBuilder server, DocServiceBuilder docServiceBuilder,
            List<ThriftServiceRegistrationBean> beans,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory,
            @Nullable String docsPath) {
        requireNonNull(server, "server");
        requireNonNull(docServiceBuilder, "docServiceBuilder");
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
            docServiceBuilder.exampleRequest(docServiceRequests);
            for (Entry<String, Collection<HttpHeaders>> entry : docServiceHeaders.entrySet()) {
                docServiceBuilder.exampleHttpHeaders(entry.getKey(), entry.getValue());
            }
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
            server.service(bean.getRoute(), service);
        });
    }

    /**
     * Adds gRPC services to the specified {@link ServerBuilder}.
     */
    public static void configureGrpcServices(
            ServerBuilder server, DocServiceBuilder docServiceBuilder,
            List<GrpcServiceRegistrationBean> beans,
            @Nullable MeterIdPrefixFunctionFactory meterIdPrefixFunctionFactory,
            @Nullable String docsPath) {
        requireNonNull(server, "server");
        requireNonNull(docServiceBuilder, "docServiceBuilder");
        requireNonNull(beans, "beans");

        final List<ExampleRequest> docServiceRequests = new ArrayList<>();
        beans.forEach(bean -> {
            final ServiceWithRoutes<HttpRequest, HttpResponse> serviceWithRoutes =
                    bean.getService();
            docServiceRequests.addAll(bean.getExampleRequests());
            serviceWithRoutes.routes().forEach(
                    route -> {
                        Service<HttpRequest, HttpResponse> service = bean.getService();
                        for (Function<Service<HttpRequest, HttpResponse>,
                                ? extends Service<HttpRequest, HttpResponse>> decorator
                                : bean.getDecorators()) {
                            service = service.decorate(decorator);
                        }
                        server.service(route,
                                       setupMetricCollectingService(service, bean,
                                                                    meterIdPrefixFunctionFactory));
                    }
            );
        });

        if (!Strings.isNullOrEmpty(docsPath)) {
            docServiceRequests.forEach(
                    exampleReq -> docServiceBuilder.exampleRequestForMethod(exampleReq.getServiceType(),
                                                                            exampleReq.getMethodName(),
                                                                            exampleReq.getExampleRequest()));
        }
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

            final KeyManagerFactory keyManagerFactory = getKeyManagerFactory(ssl, keyStoreSupplier);
            final TrustManagerFactory trustManagerFactory = getTrustManagerFactory(ssl, trustStoreSupplier);

            sb.tls(keyManagerFactory, sslContextBuilder -> {
                sslContextBuilder.trustManager(trustManagerFactory);

                final SslProvider sslProvider = ssl.getProvider();
                if (sslProvider != null) {
                    sslContextBuilder.sslProvider(sslProvider);
                }
                final List<String> enabledProtocols = ssl.getEnabledProtocols();
                if (enabledProtocols != null) {
                    sslContextBuilder.protocols(enabledProtocols.toArray(EMPTY_PROTOCOL_NAMES));
                }
                final List<String> ciphers = ssl.getCiphers();
                if (ciphers != null) {
                    sslContextBuilder.ciphers(ImmutableList.copyOf(ciphers),
                                              SupportedCipherSuiteFilter.INSTANCE);
                }
                final ClientAuth clientAuth = ssl.getClientAuth();
                if (clientAuth != null) {
                    sslContextBuilder.clientAuth(clientAuth);
                }
            });
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

        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        if (ssl.getKeyAlias() != null) {
            keyManagerFactory = new CustomAliasKeyManagerFactory(keyManagerFactory, ssl.getKeyAlias());
        }

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

    /**
     * Configures a decorator for encoding the content of the HTTP responses sent from the server.
     */
    public static Function<Service<HttpRequest, HttpResponse>,
            HttpEncodingService> contentEncodingDecorator(@Nullable String[] mimeTypes,
                                                          @Nullable String[] excludedUserAgents,
                                                          long minBytesToForceChunkedAndEncoding) {
        final Predicate<MediaType> encodableContentTypePredicate;
        if (mimeTypes == null || mimeTypes.length == 0) {
            encodableContentTypePredicate = contentType -> true;
        } else {
            final List<MediaType> encodableContentTypes =
                    Arrays.stream(mimeTypes).map(MediaType::parse).collect(toImmutableList());
            encodableContentTypePredicate = contentType ->
                    encodableContentTypes.stream().anyMatch(contentType::is);
        }

        final Predicate<HttpHeaders> encodableRequestHeadersPredicate;
        if (excludedUserAgents == null || excludedUserAgents.length == 0) {
            encodableRequestHeadersPredicate = headers -> true;
        } else {
            final List<Pattern> patterns =
                    Arrays.stream(excludedUserAgents).map(Pattern::compile).collect(toImmutableList());
            encodableRequestHeadersPredicate = headers -> {
                // No User-Agent header will be converted to an empty string.
                final String userAgent = headers.get(HttpHeaderNames.USER_AGENT, "");
                return patterns.stream().noneMatch(pattern -> pattern.matcher(userAgent).matches());
            };
        }

        return delegate -> new HttpEncodingService(delegate,
                                                   encodableContentTypePredicate,
                                                   encodableRequestHeadersPredicate,
                                                   minBytesToForceChunkedAndEncoding);
    }

    /**
     * Parses the data size text as a decimal {@code long}.
     *
     * @param dataSizeText the data size text, i.e. {@code 1}, {@code 1B}, {@code 1KB}, {@code 1MB},
     *                     {@code 1GB} or {@code 1TB}
     */
    public static long parseDataSize(String dataSizeText) {
        requireNonNull(dataSizeText, "text");
        final Matcher matcher = DATA_SIZE_PATTERN.matcher(dataSizeText);
        checkArgument(matcher.matches(),
                      "Invalid data size text: %s (expected: %s)",
                      dataSizeText, DATA_SIZE_PATTERN);

        final long unit;
        final String unitText = matcher.group(2);
        if (Strings.isNullOrEmpty(unitText)) {
            unit = 1L;
        } else {
            switch (Ascii.toLowerCase(unitText)) {
                case "b":
                    unit = 1L;
                    break;
                case "kb":
                    unit = 1024L;
                    break;
                case "mb":
                    unit = 1024L * 1024L;
                    break;
                case "gb":
                    unit = 1024L * 1024L * 1024L;
                    break;
                case "tb":
                    unit = 1024L * 1024L * 1024L * 1024L;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid data size text: " + dataSizeText +
                                                       " (expected: " + DATA_SIZE_PATTERN + ')');
            }
        }
        try {
            final long amount = Long.parseLong(matcher.group(1));
            return LongMath.checkedMultiply(amount, unit);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid data size text: " + dataSizeText +
                                               " (expected: " + DATA_SIZE_PATTERN + ')', e);
        }
    }

    private ArmeriaConfigurationUtil() {}
}
