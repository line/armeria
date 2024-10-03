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
package com.linecorp.armeria.spring.web.reactive;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.configureServerWithArmeriaSettings;
import static com.linecorp.armeria.internal.spring.ArmeriaConfigurationUtil.contentEncodingDecorator;
import static java.util.Objects.requireNonNull;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.reactive.server.AbstractReactiveWebServerFactory;
import org.springframework.boot.web.reactive.server.ReactiveWebServerFactory;
import org.springframework.boot.web.server.Compression;
import org.springframework.boot.web.server.Http2;
import org.springframework.boot.web.server.Ssl;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.HttpHandler;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.spring.ArmeriaServerConfigurator;
import com.linecorp.armeria.spring.ArmeriaSettings;
import com.linecorp.armeria.spring.InternalServices;
import com.linecorp.armeria.spring.MetricCollectingServiceConfigurator;
import com.linecorp.armeria.spring.internal.common.DataBufferFactoryWrapper;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.handler.ssl.ClientAuth;
import reactor.core.Disposable;

/**
 * A {@link ReactiveWebServerFactory} which is used to create a new {@link ArmeriaWebServer}.
 */
public class ArmeriaReactiveWebServerFactory extends AbstractReactiveWebServerFactory {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaReactiveWebServerFactory.class);

    private final ConfigurableListableBeanFactory beanFactory;
    private final Environment environment;

    /**
     * Creates a new factory instance with the specified {@link ConfigurableListableBeanFactory}.
     */
    public ArmeriaReactiveWebServerFactory(ConfigurableListableBeanFactory beanFactory,
                                           Environment environment) {
        this.beanFactory = requireNonNull(beanFactory, "beanFactory");
        this.environment = environment;
    }

    private static com.linecorp.armeria.spring.Ssl toArmeriaSslConfiguration(Ssl ssl) {
        if (!ssl.isEnabled()) {
            return new com.linecorp.armeria.spring.Ssl();
        }

        ClientAuth clientAuth = null;
        if (ssl.getClientAuth() != null) {
            switch (ssl.getClientAuth()) {
                case NEED:
                    clientAuth = ClientAuth.REQUIRE;
                    break;
                case WANT:
                    clientAuth = ClientAuth.OPTIONAL;
                    break;
            }
        }
        return new com.linecorp.armeria.spring.Ssl()
                .setEnabled(ssl.isEnabled())
                .setClientAuth(clientAuth)
                .setCiphers(ssl.getCiphers() != null ? ImmutableList.copyOf(ssl.getCiphers()) : null)
                .setEnabledProtocols(ssl.getEnabledProtocols() != null ? ImmutableList.copyOf(
                        ssl.getEnabledProtocols()) : null)
                .setKeyAlias(ssl.getKeyAlias())
                .setKeyPassword(ssl.getKeyPassword())
                .setKeyStore(ssl.getKeyStore())
                .setKeyStorePassword(ssl.getKeyStorePassword())
                .setKeyStoreType(ssl.getKeyStoreType())
                .setKeyStoreProvider(ssl.getKeyStoreProvider())
                .setTrustStore(ssl.getTrustStore())
                .setTrustStorePassword(ssl.getTrustStorePassword())
                .setTrustStoreType(ssl.getTrustStoreType())
                .setTrustStoreProvider(ssl.getTrustStoreProvider());
    }

    @Override
    public WebServer getWebServer(HttpHandler httpHandler) {
        final ArmeriaWebServer armeriaWebServerBean = findBean(ArmeriaWebServer.class);
        if (armeriaWebServerBean != null) {
            return armeriaWebServerBean;
        }

        final Http2 http2 = getHttp2();
        if (http2 != null && !http2.isEnabled()) {
            logger.warn("Cannot disable HTTP/2 protocol for Armeria server. It will be enabled automatically.");
        }

        final ServerBuilder sb = Server.builder();
        sb.disableServerHeader();
        sb.disableDateHeader();

        final ArmeriaSettings armeriaSettings = findBean(ArmeriaSettings.class);
        if (!isArmeriaCompressionEnabled(armeriaSettings)) {
            final Compression compression = getCompression();
            if (compression != null && compression.getEnabled()) {
                final long minResponseSize = compression.getMinResponseSize().toBytes();
                sb.decorator(contentEncodingDecorator(compression.getMimeTypes(),
                                                      compression.getExcludedUserAgents(),
                                                      Ints.saturatedCast(minResponseSize)));
            }
        }

        if (armeriaSettings != null) {
            final MeterRegistry meterRegistry = firstNonNull(findBean(MeterRegistry.class),
                                                             Flags.meterRegistry());
            final InternalServices internalServices = findBean(InternalServices.class);
            assert internalServices != null;
            configureServerWithArmeriaSettings(sb, armeriaSettings,
                                               internalServices,
                                               findBeans(ArmeriaServerConfigurator.class),
                                               findBeans(Consumer.class, ServerBuilder.class),
                                               meterRegistry,
                                               meterIdPrefixFunctionOrDefault(),
                                               findBeans(MetricCollectingServiceConfigurator.class),
                                               findBeans(DependencyInjector.class),
                                               findBeans(ServerErrorHandler.class),
                                               beanFactory);
        }

        // In the property file, both Spring and Armeria port configuration can coexist.
        // We use following to define primaryAddress, primaryLocalPort and primarySessionProtocol:
        // (we consider Spring port is not specified when it's 0.)
        // 1) Both Armeria and Spring port were specified:
        //    - primaryAddress = Spring address
        //    - primaryLocalPort = Spring port
        //    - primarySessionProtocol = depends on the springSsl.isEnabled()
        // 2) Only Spring port was specified:
        //    - primaryAddress = Spring address
        //    - primaryLocalPort = Spring port
        //    - primarySessionProtocol = depends on the springSsl.isEnabled()
        // 3) Only Armeria port was specified:
        //    - primaryAddress = null;
        //    - primaryLocalPort = the port of the first Armeria Port
        //    - primarySessionProtocol = the session protocol of the first Armeria Port
        // 4) No port was specified:
        //    a) The port is configured by other ways (e.g. ArmeriaServerConfigurator)
        //       - primaryAddress = null
        //       - primaryLocalPort = the port of the first Armeria Port
        //       - primarySessionProtocol = the session protocol of the first Armeria Port
        //    b) The port is not specified.
        //       - primaryAddress = Spring address
        //       - primaryLocalPort = Spring port
        //       - primarySessionProtocol = depends on the armeriaSsl.isEnabled().
        //                               If armeriaSsl is null use SpringSsl
        //
        // Please note that Armeria TLS configuration has precedence over Spring SSL.

        final boolean armeriaSslEnabled = isArmeriaSslEnabled(armeriaSettings);
        final Ssl springSsl = getSsl();
        final SessionProtocol springSessionProtocol;
        if (springSsl != null && springSsl.isEnabled()) {
            if (armeriaSslEnabled) {
                // TLS will be applied in configureServerWithArmeriaSettings.
                logger.warn("Both Armeria and Spring TLS configuration exist. " +
                            "Armeria TLS configuration is used.");
            } else {
                TlsUtil.configureTls(sb, toArmeriaSslConfiguration(springSsl), this);
            }
            springSessionProtocol = SessionProtocol.HTTPS;
        } else {
            springSessionProtocol = SessionProtocol.HTTP;
        }

        final int springPort = ensureValidPort(getPort());
        final List<ServerPort> armeriaPorts = armeriaPorts(sb);
        final InetAddress primaryAddress;
        final int primaryLocalPort;
        final SessionProtocol primarySessionProtocol;
        if (springPort > 0 || armeriaPorts.isEmpty()) {
            // The cases of 1, 2, 4.b
            primaryAddress = getAddress();
            primaryLocalPort = springPort;
            if (springPort == 0) {
                // case 4.b
                primarySessionProtocol = armeriaSslEnabled ? SessionProtocol.HTTPS : springSessionProtocol;
            } else {
                // cases 1, 2
                primarySessionProtocol = springSessionProtocol;
            }
            if (primaryAddress != null) {
                sb.port(new InetSocketAddress(primaryAddress, primaryLocalPort), primarySessionProtocol);
            } else {
                sb.port(primaryLocalPort, primarySessionProtocol);
            }
        } else {
            // The cases of 3, 4.a
            final ServerPort armeriaFirstPort = armeriaPorts.get(0);
            final InetSocketAddress inetSocketAddress = armeriaFirstPort.localAddress();
            primaryAddress = null;
            primaryLocalPort = inetSocketAddress.getPort();
            primarySessionProtocol = armeriaFirstPort.hasTls() ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
        }

        final DataBufferFactoryWrapper<?> factoryWrapper =
                firstNonNull(findBean(DataBufferFactoryWrapper.class), DataBufferFactoryWrapper.DEFAULT);

        final Server server = configureService(sb, httpHandler, factoryWrapper, getServerHeader()).build();
        final ArmeriaWebServer armeriaWebServer = new ArmeriaWebServer(server, primarySessionProtocol,
                                                                       primaryAddress,
                                                                       primaryLocalPort, beanFactory);
        if (!isManagementPortEqualsToServerPort()) {
            // The management port is set to the Server in ArmeriaSpringActuatorAutoConfiguration.
            // Since this method will be called twice, need to reuse ArmeriaWebServer.
            beanFactory.registerSingleton("armeriaWebServer", armeriaWebServer);
        }

        return armeriaWebServer;
    }

    private static List<ServerPort> armeriaPorts(ServerBuilder sb) {
        try {
            final Field ports = ServerBuilder.class.getDeclaredField("ports");
            ports.setAccessible(true);
            @SuppressWarnings("unchecked")
            final List<ServerPort> armeriaPorts = (List<ServerPort>) ports.get(sb);
            return armeriaPorts;
        } catch (NoSuchFieldException ignored) {
            throw new Error(); // Should never reach here.
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Failed to get ports in " + ServerBuilder.class.getSimpleName() +
                                            " via reflection.", e);
        }
    }

    private static boolean isArmeriaSslEnabled(@Nullable ArmeriaSettings armeriaSettings) {
        if (armeriaSettings != null) {
            final com.linecorp.armeria.spring.Ssl ssl = armeriaSettings.getSsl();
            if (ssl != null) {
                return ssl.isEnabled();
            }
        }
        return false;
    }

    private static boolean isArmeriaCompressionEnabled(@Nullable ArmeriaSettings armeriaSettings) {
        if (armeriaSettings != null) {
            final ArmeriaSettings.Compression compression = armeriaSettings.getCompression();
            if (compression != null) {
                return compression.isEnabled();
            }
        }
        return false;
    }

    private MeterIdPrefixFunction meterIdPrefixFunctionOrDefault() {
        final MeterIdPrefixFunction f = findBean(MeterIdPrefixFunction.class);
        return f != null ? f : MeterIdPrefixFunction.ofDefault("armeria.server");
    }

    @VisibleForTesting
    boolean isManagementPortEqualsToServerPort() {
        final Integer managementPort = environment.getProperty("management.server.port", Integer.class);
        if (managementPort == null) {
            // The management port is disable
            return true;
        }
        final Integer ensuredManagementPort = ensureValidPort(managementPort);
        final Integer serverPort = environment.getProperty("server.port", Integer.class);
        return (serverPort == null && ensuredManagementPort.equals(8080)) ||
               (ensuredManagementPort != 0 && ensuredManagementPort.equals(serverPort));
    }

    private static ServerBuilder configureService(ServerBuilder sb, HttpHandler httpHandler,
                                                  DataBufferFactoryWrapper<?> factoryWrapper,
                                                  @Nullable String serverHeader) {
        final ArmeriaHttpHandlerAdapter handler =
                new ArmeriaHttpHandlerAdapter(httpHandler, factoryWrapper);
        return sb.route()
                 .addRoute(Route.ofCatchAll())
                 .defaultServiceName("SpringWebFlux")
                 .build((ctx, req) -> {
                     final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                     final HttpResponse response = HttpResponse.of(future);
                     final Disposable disposable = handler.handle(ctx, req, future, serverHeader).subscribe();
                     response.whenComplete().handle((unused, cause) -> {
                         if (cause != null) {
                             if (ctx.method() != HttpMethod.HEAD) {
                                 logger.debug("{} Response stream has been cancelled.", ctx, cause);
                             }
                             disposable.dispose();
                         }
                         return null;
                     });
                     return response;
                 });
    }

    @Nullable
    private <T> T findBean(Class<T> clazz) {
        try {
            return beanFactory.getBean(clazz);
        } catch (NoUniqueBeanDefinitionException e) {
            throw new IllegalStateException("Too many " + clazz.getSimpleName() + " beans: (expected: 1)", e);
        } catch (NoSuchBeanDefinitionException e) {
            return null;
        }
    }

    private <T> List<T> findBeans(Class<T> clazz) {
        final String[] names = beanFactory.getBeanNamesForType(clazz);
        if (names.length == 0) {
            return ImmutableList.of();
        }
        return Arrays.stream(names)
                     .map(name -> beanFactory.getBean(name, clazz))
                     .collect(toImmutableList());
    }

    private <T> List<T> findBeans(Class<?> containerType, Class<?> genericType) {
        final ResolvableType resolvableType = ResolvableType.forClassWithGenerics(containerType, genericType);
        final String[] names = beanFactory.getBeanNamesForType(resolvableType);
        if (names.length == 0) {
            return ImmutableList.of();
        }
        @SuppressWarnings("unchecked")
        final List<T> beans = (List<T>) Arrays.stream(names).map(beanFactory::getBean)
                                              .collect(toImmutableList());
        return beans;
    }

    private static int ensureValidPort(int port) {
        checkArgument(port >= 0 && port <= 65535,
                      "port: %s (expected: 0[arbitrary port] or 1-65535)", port);
        return port;
    }
}
