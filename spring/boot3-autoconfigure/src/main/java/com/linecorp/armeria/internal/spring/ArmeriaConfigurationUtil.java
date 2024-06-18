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
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationNetUtil.configurePorts;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationSettingsUtil.configureSettings;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.util.ResourceUtils;

import com.google.common.base.Ascii;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;
import com.linecorp.armeria.server.metric.MetricCollectingServiceBuilder;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.ArmeriaSettings.InternalServiceProperties;
import com.linecorp.armeria.spring.ArmeriaSettings.Port;
import com.linecorp.armeria.spring.InternalServiceId;
import com.linecorp.armeria.spring.InternalServices;
import com.linecorp.armeria.spring.MetricCollectingServiceConfigurator;
import com.linecorp.armeria.spring.SpringDependencyInjector;
import com.linecorp.armeria.spring.Ssl;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;

/**
 * A utility class which is used to configure a {@link ServerBuilder} with the {@link ArmeriaSettings} and
 * service registration beans.
 */
public final class ArmeriaConfigurationUtil {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaConfigurationUtil.class);

    private static final String[] EMPTY_PROTOCOL_NAMES = new String[0];

    /**
     * The pattern for data size text.
     */
    private static final Pattern DATA_SIZE_PATTERN = Pattern.compile("^([+]?\\d+)([a-zA-Z]{0,2})$");

    /**
     * Sets graceful shutdown timeout, health check service and {@link MeterRegistry} for the specified
     * {@link ServerBuilder}.
     */
    public static void configureServerWithArmeriaSettings(
            ServerBuilder server,
            ArmeriaSettings settings,
            InternalServices internalServices,
            List<ArmeriaServerConfigurator> armeriaServerConfigurators,
            List<Consumer<ServerBuilder>> armeriaServerBuilderConsumers,
            MeterRegistry meterRegistry,
            MeterIdPrefixFunction meterIdPrefixFunction,
            List<MetricCollectingServiceConfigurator> metricCollectingServiceConfigurators,
            List<DependencyInjector> dependencyInjectors,
            List<ServerErrorHandler> serverErrorHandlers,
            BeanFactory beanFactory) {

        requireNonNull(server, "server");
        requireNonNull(settings, "settings");
        requireNonNull(armeriaServerConfigurators, "armeriaServerConfigurators");
        requireNonNull(armeriaServerBuilderConsumers, "armeriaServerBuilderConsumers");
        requireNonNull(meterRegistry, "meterRegistry");
        requireNonNull(metricCollectingServiceConfigurators, "metricCollectingServiceConfigurators");

        final Port internalServicePort = internalServices.internalServicePort();
        final Port managementServerPort = internalServices.managementServerPort();
        final ImmutableList.Builder<Port> internalPortsBuilder = ImmutableList.builder();
        if (internalServicePort != null) {
            internalPortsBuilder.add(internalServicePort);
        }

        final boolean needsManagementPort =
                findBean(beanFactory, "actuatorServerConfigurator", ArmeriaServerConfigurator.class) != null;

        if (needsManagementPort && managementServerPort != null) {
            internalPortsBuilder.add(managementServerPort);
        }
        final List<Port> internalPorts = dedupPorts(internalPortsBuilder.build());
        configurePorts(server, dedupPorts(settings.getPorts()));
        configurePorts(server, internalPorts);
        configureSettings(server, settings);

        if (settings.getGracefulShutdownQuietPeriodMillis() >= 0 &&
            settings.getGracefulShutdownTimeoutMillis() >= 0) {
            server.gracefulShutdownTimeoutMillis(settings.getGracefulShutdownQuietPeriodMillis(),
                                                 settings.getGracefulShutdownTimeoutMillis());
            logger.debug("Set graceful shutdown timeout: quiet period {} ms, timeout {} ms",
                         settings.getGracefulShutdownQuietPeriodMillis(),
                         settings.getGracefulShutdownTimeoutMillis());
        }

        final InternalServiceProperties internalServiceProperties = settings.getInternalServices();
        final List<InternalServiceId> internalServiceIds;
        if (internalServiceProperties == null) {
            internalServiceIds = InternalServiceId.defaultServiceIds();
        } else {
            internalServiceIds = internalServiceProperties.getInclude();
        }

        configureInternalService(server, InternalServiceId.DOCS, settings.getDocsPath(),
                                 internalServices.docService(),
                                 internalPorts, internalServiceIds, true);

        configureInternalService(server, InternalServiceId.HEALTH, settings.getHealthCheckPath(),
                                 internalServices.healthCheckService(),
                                 internalPorts, internalServiceIds, false);

        server.meterRegistry(meterRegistry);

        if (settings.getSsl() != null) {
            configureTls(server, settings.getSsl());
        }

        final ArmeriaSettings.Compression compression = settings.getCompression();
        if (compression != null && compression.isEnabled()) {
            final int minBytesToForceChunkedAndEncoding =
                    Ints.saturatedCast(parseDataSize(compression.getMinResponseSize()));
            server.decorator(contentEncodingDecorator(compression.getMimeTypes(),
                                                      compression.getExcludedUserAgents(),
                                                      minBytesToForceChunkedAndEncoding));
        }

        if (settings.isEnableMetrics()) {
            if (!metricCollectingServiceConfigurators.isEmpty()) {
                final MetricCollectingServiceBuilder builder = MetricCollectingService
                        .builder(meterIdPrefixFunction);
                for (MetricCollectingServiceConfigurator configurator : metricCollectingServiceConfigurators) {
                    configurator.configure(builder);
                }
                server.decorator(builder.newDecorator());
            } else {
                server.decorator(MetricCollectingService.newDecorator(meterIdPrefixFunction));
            }

            configureInternalService(server, InternalServiceId.METRICS, settings.getMetricsPath(),
                                     internalServices.metricsExpositionService(), internalPorts,
                                     internalServiceIds, false);
        }

        // dependencyInjectors from beans are added first, which will be used first thing,
        // then from armeriaServerConfigurators and armeriaServerBuilderConsumers.
        dependencyInjectors.forEach(injector -> {
            server.dependencyInjector(injector, false); // The injector is closed by Spring.
        });
        if (settings.isEnableAutoInjection()) {
            server.dependencyInjector(SpringDependencyInjector.of(beanFactory), false);
        }
        // serverErrorHandlers from beans are added first, which will be used first thing,
        // then from armeriaServerConfigurators and armeriaServerBuilderConsumers.
        serverErrorHandlers.forEach(server::errorHandler);

        // armeriaServerConfigurators and armeriaServerBuilderConsumers will override the properties set
        // via ArmeriaSettings.
        armeriaServerConfigurators.forEach(configurator -> configurator.configure(server));
        armeriaServerBuilderConsumers.forEach(consumer -> consumer.accept(server));
    }

    private static void configureInternalService(ServerBuilder server, InternalServiceId serviceId,
                                                 @Nullable String servicePath,
                                                 @Nullable HttpService service,
                                                 List<Port> internalPorts,
                                                 @Nullable List<InternalServiceId> internalServiceIds,
                                                 boolean usesPrefixPath) {
        if (service == null) {
            return;
        }
        // An internal service should be created only when a servicePath is not null.
        assert servicePath != null;
        internalServiceIds = firstNonNull(internalServiceIds, ImmutableList.of());

        if (internalPorts.isEmpty()) {
            // No internal ports are configured. The default virtual is used to use the service.
            configureInternalService(server, serviceId, servicePath, service,
                                     (Port) null, internalServiceIds, usesPrefixPath);
        } else {
            for (Port internalPort : internalPorts) {
                configureInternalService(server, serviceId, servicePath, service,
                                         internalPort, internalServiceIds, usesPrefixPath);
            }
        }
    }

    private static void configureInternalService(ServerBuilder server, InternalServiceId serviceId,
                                                 String servicePath,
                                                 HttpService service, @Nullable Port internalPort,
                                                 List<InternalServiceId> internalServiceIds,
                                                 boolean usesPrefixPath) {
        final boolean needsPortBasedVirtualHost;
        if (internalPort == null) {
            needsPortBasedVirtualHost = false;
        } else {
            if (internalServiceIds.contains(InternalServiceId.ALL)) {
                // All internal services use the internal port.
                needsPortBasedVirtualHost = true;
            } else {
                // The service specified in internalServiceIds uses the internal port.
                needsPortBasedVirtualHost = internalServiceIds.contains(serviceId);
            }
        }

        if (needsPortBasedVirtualHost) {
            if (usesPrefixPath) {
                server.virtualHost(internalPort.getPort()).serviceUnder(servicePath, service);
            } else {
                server.virtualHost(internalPort.getPort()).service(servicePath, service);
            }
        } else {
            if (usesPrefixPath) {
                server.serviceUnder(servicePath, service);
            } else {
                server.service(servicePath, service);
            }
        }
    }

    @Nullable
    private static <T> T findBean(BeanFactory beanFactory, String name, Class<T> clazz) {
        try {
            return beanFactory.getBean(name, clazz);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("Too many " + clazz.getSimpleName() + " beans: (expected: 1)", e);
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
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

            sb.tls(keyManagerFactory);
            sb.tlsCustomizer(sslContextBuilder -> {
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
    public static Function<? super HttpService, EncodingService> contentEncodingDecorator(
            @Nullable String[] mimeTypes, @Nullable String[] excludedUserAgents,
            int minBytesToForceChunkedAndEncoding) {
        final Predicate<MediaType> encodableContentTypePredicate;
        if (mimeTypes == null || mimeTypes.length == 0) {
            encodableContentTypePredicate = contentType -> true;
        } else {
            final List<MediaType> encodableContentTypes =
                    Arrays.stream(mimeTypes).map(MediaType::parse).collect(toImmutableList());
            encodableContentTypePredicate = contentType ->
                    encodableContentTypes.stream().anyMatch(contentType::is);
        }

        final Predicate<? super RequestHeaders> encodableRequestHeadersPredicate;
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

        return EncodingService.builder()
                              .encodableContentTypes(encodableContentTypePredicate)
                              .encodableRequestHeaders(encodableRequestHeadersPredicate)
                              .minBytesToForceChunkedEncoding(minBytesToForceChunkedAndEncoding)
                              .newDecorator();
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

    /**
     * Remove duplicate {@link Port} entries within the list.
     * If there are duplicates, keep the preceding {@link Port}.
     * The criteria for duplication are based on {@link Port#getPort()}, {@link Port#getAddress()},and
     * {@link Port#getIp()}.
     */
    private static List<Port> dedupPorts(List<Port> ports) {
        final List<Port> dedupedList = new ArrayList<>();
        for (Port port : ports) {
            boolean found = false;
            for (Port deduped : dedupedList) {
                if (port.getPort() == deduped.getPort() &&
                    Objects.equal(port.getAddress(), deduped.getAddress()) &&
                    Objects.equal(port.getIp(), deduped.getIp())) {
                    found = true;
                    if (port.getProtocols() != null) {
                        final Set<SessionProtocol> merged = EnumSet.copyOf(port.getProtocols());
                        if (deduped.getProtocols() != null) {
                            merged.addAll(deduped.getProtocols());
                        }
                        deduped.setProtocols(ImmutableList.copyOf(merged));
                    }
                    break;
                }
            }
            if (!found) {
                dedupedList.add(port);
            }
        }
        return ImmutableList.copyOf(dedupedList);
    }

    private ArmeriaConfigurationUtil() {}
}
