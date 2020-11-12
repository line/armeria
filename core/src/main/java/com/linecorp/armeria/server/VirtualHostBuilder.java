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

package com.linecorp.armeria.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.ServiceConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServiceConfig.validateRequestTimeoutMillis;
import static com.linecorp.armeria.server.VirtualHost.HOSTNAME_PATTERN;
import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ReferenceCountUtil;

/**
 * Builds a new {@link VirtualHost}.
 *
 * <p>This class can only be instantiated through the {@link ServerBuilder#defaultVirtualHost()} or
 * {@link ServerBuilder#virtualHost(String)} method of the {@link ServerBuilder}.
 *
 * <p>Call {@link #and()} method and return to {@link ServerBuilder}.
 *
 * @see ServerBuilder
 * @see Route
 */
public final class VirtualHostBuilder {

    private final ServerBuilder serverBuilder;
    private final boolean defaultVirtualHost;
    private final List<ServiceConfigSetters> serviceConfigSetters = new ArrayList<>();

    @Nullable
    private String defaultHostname;
    @Nullable
    private String hostnamePattern;
    @Nullable
    private Supplier<SslContextBuilder> sslContextBuilderSupplier;
    @Nullable
    private Boolean tlsSelfSigned;
    @Nullable
    private SelfSignedCertificate selfSignedCertificate;
    private final List<Consumer<? super SslContextBuilder>> tlsCustomizers = new ArrayList<>();
    private final List<RouteDecoratingService> routeDecoratingServices = new LinkedList<>();
    @Nullable
    private Function<? super VirtualHost, ? extends Logger> accessLoggerMapper;

    @Nullable
    private RejectedRouteHandler rejectedRouteHandler;
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private AccessLogWriter accessLogWriter;
    private boolean shutdownAccessLogWriterOnStop;
    @Nullable
    private AnnotatedServiceExtensions annotatedServiceExtensions;

    /**
     * Creates a new {@link VirtualHostBuilder}.
     *
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}
     * @param defaultVirtualHost tells whether this builder is the default virtual host builder or not
     */
    VirtualHostBuilder(ServerBuilder serverBuilder, boolean defaultVirtualHost) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
        this.defaultVirtualHost = defaultVirtualHost;
    }

    /**
     * Returns the parent {@link ServerBuilder}.
     *
     * @return serverBuilder the parent {@link ServerBuilder}.
     */
    public ServerBuilder and() {
        return serverBuilder;
    }

    /**
     * Sets the default hostname of this {@link VirtualHost}.
     */
    public VirtualHostBuilder defaultHostname(String defaultHostname) {
        this.defaultHostname = normalizeDefaultHostname(defaultHostname);
        return this;
    }

    /**
     * Sets the hostname pattern of this {@link VirtualHost}.
     *
     * @throws UnsupportedOperationException if this is the default {@link VirtualHostBuilder}
     */
    public VirtualHostBuilder hostnamePattern(String hostnamePattern) {
        if (defaultVirtualHost) {
            throw new UnsupportedOperationException(
                    "Cannot set hostnamePattern for the default virtual host builder");
        }

        checkArgument(!hostnamePattern.isEmpty(), "hostnamePattern is empty.");

        final boolean validHostnamePattern;
        if (hostnamePattern.charAt(0) == '*') {
            validHostnamePattern =
                    hostnamePattern.length() >= 3 &&
                    hostnamePattern.charAt(1) == '.' &&
                    HOSTNAME_PATTERN.matcher(hostnamePattern.substring(2)).matches();
        } else {
            validHostnamePattern = HOSTNAME_PATTERN.matcher(hostnamePattern).matches();
        }

        checkArgument(validHostnamePattern,
                      "hostnamePattern: %s (expected: *.<hostname> or <hostname>)", hostnamePattern);

        this.hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile) {
        return tls(keyCertChainFile, keyFile, null);
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        requireNonNull(keyFile, "keyFile");
        return tls(() -> SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword));
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainInputStream} and
     * cleartext {@code keyInputStream}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return tls(keyCertChainInputStream, keyInputStream, null);
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainInputStream},
     * {@code keyInputStream} and {@code keyPassword}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream,
                                  @Nullable String keyPassword) {
        requireNonNull(keyCertChainInputStream, "keyCertChainInputStream");
        requireNonNull(keyInputStream, "keyInputStream");

        // Retrieve the content of the given streams so that they can be consumed more than once.
        final byte[] keyCertChain;
        final byte[] key;
        try {
            keyCertChain = ByteStreams.toByteArray(keyCertChainInputStream);
            key = ByteStreams.toByteArray(keyInputStream);
        } catch (IOException e) {
            throw new IOError(e);
        }

        return tls(() -> SslContextBuilder.forServer(new ByteArrayInputStream(keyCertChain),
                                                     new ByteArrayInputStream(key),
                                                     keyPassword));
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(PrivateKey key, X509Certificate... keyCertChain) {
        return tls(key, null, keyCertChain);
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified cleartext {@link PrivateKey} and
     * {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        return tls(key, null, keyCertChain);
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified cleartext {@link PrivateKey},
     * {@code keyPassword} and {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                  X509Certificate... keyCertChain) {
        return tls(key, keyPassword, ImmutableList.copyOf(requireNonNull(keyCertChain, "keyCertChain")));
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified cleartext {@link PrivateKey},
     * {@code keyPassword} and {@link X509Certificate} chain.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                  Iterable<? extends X509Certificate> keyCertChain) {
        requireNonNull(key, "key");
        requireNonNull(keyCertChain, "keyCertChain");
        for (X509Certificate keyCert : keyCertChain) {
            requireNonNull(keyCert, "keyCertChain contains null.");
        }

        final X509Certificate[] keyCertChainArray = Iterables.toArray(keyCertChain, X509Certificate.class);
        return tls(() -> SslContextBuilder.forServer(key, keyPassword, keyCertChainArray));
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@link KeyManagerFactory}.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tls(KeyManagerFactory keyManagerFactory) {
        requireNonNull(keyManagerFactory, "keyManagerFactory");
        return tls(() -> SslContextBuilder.forServer(keyManagerFactory));
    }

    private VirtualHostBuilder tls(Supplier<SslContextBuilder> sslContextBuilderSupplier) {
        requireNonNull(sslContextBuilderSupplier, "sslContextBuilderSupplier");
        checkState(this.sslContextBuilderSupplier == null, "TLS has been configured already.");
        this.sslContextBuilderSupplier = sslContextBuilderSupplier;
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tlsSelfSigned() {
        tlsSelfSigned = true;
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tlsSelfSigned(boolean tlsSelfSigned) {
        this.tlsSelfSigned = tlsSelfSigned;
        return this;
    }

    /**
     * Adds the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder} that will be
     * applied to the SSL session.
     */
    public VirtualHostBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        requireNonNull(tlsCustomizer, "tlsCustomizer");
        tlsCustomizers.add(tlsCustomizer);
        return this;
    }

    /**
     * Configures an {@link HttpService} of the {@link VirtualHost} with the {@code customizer}.
     */
    public VirtualHostBuilder withRoute(Consumer<? super VirtualHostServiceBindingBuilder> customizer) {
        final VirtualHostServiceBindingBuilder builder = new VirtualHostServiceBindingBuilder(this);
        customizer.accept(builder);
        return this;
    }

    /**
     * Returns a {@link ServiceBindingBuilder} which is for binding an {@link HttpService} fluently.
     */
    public VirtualHostServiceBindingBuilder route() {
        return new VirtualHostServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link VirtualHostDecoratingServiceBindingBuilder} which is for binding
     * a {@code decorator} fluently.
     */
    public VirtualHostDecoratingServiceBindingBuilder routeDecorator() {
        return new VirtualHostDecoratingServiceBindingBuilder(this);
    }

    /**
     * Binds the specified {@link HttpService} under the specified directory.
     */
    public VirtualHostBuilder serviceUnder(String pathPrefix, HttpService service) {
        service(Route.builder().pathPrefix(pathPrefix).build(), service);
        return this;
    }

    /**
     * Binds the specified {@link HttpService} at the specified path pattern. e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     *   <li>{@code exact:/foo/bar} (exact match)</li>
     *   <li>{@code prefix:/files} (prefix match)</li>
     *   <li><code>glob:/~&#42;/downloads/**</code> (glob pattern)</li>
     *   <li>{@code regex:^/files/(?<filePath>.*)$} (regular expression)</li>
     * </ul>
     *
     * @throws IllegalArgumentException if the specified path pattern is invalid
     */
    public VirtualHostBuilder service(String pathPattern, HttpService service) {
        service(Route.builder().path(pathPattern).build(), service);
        return this;
    }

    /**
     * Binds the specified {@link HttpService} at the specified {@link Route}.
     */
    public VirtualHostBuilder service(Route route, HttpService service) {
        return addServiceConfigSetters(new ServiceConfigBuilder(route, service));
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    public VirtualHostBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        HttpService decorated = serviceWithRoutes;
        for (Function<? super HttpService, ? extends HttpService> d : decorators) {
            checkNotNull(d, "decorators contains null: %s", decorators);
            decorated = d.apply(decorated);
            checkNotNull(decorated, "A decorator returned null: %s", d);
        }

        final HttpService finalDecorated = decorated;
        serviceWithRoutes.routes().forEach(route -> service(route, finalDecorated));
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @SafeVarargs
    public final VirtualHostBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        return service(serviceWithRoutes, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public VirtualHostBuilder annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(Object service,
                                               Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(
            Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return annotatedService("/", service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Iterable<?> exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, Function.identity(),
                                requireNonNull(exceptionHandlersAndConverters,
                                               "exceptionHandlersAndConverters"));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Function<? super HttpService, ? extends HttpService> decorator,
                                               Iterable<?> exceptionHandlersAndConverters) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlersAndConverters, "exceptionHandlersAndConverters");
        final AnnotatedServiceExtensions configurator =
                AnnotatedServiceExtensions
                        .ofExceptionHandlersAndConverters(exceptionHandlersAndConverters);
        return annotatedService(pathPrefix, service, decorator, configurator.exceptionHandlers(),
                                configurator.requestConverters(), configurator.responseConverters());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     */
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        return annotatedService().pathPrefix(pathPrefix)
                                 .decorator(decorator)
                                 .exceptionHandlers(exceptionHandlerFunctions)
                                 .requestConverters(requestConverterFunctions)
                                 .responseConverters(responseConverterFunctions)
                                 .build(service);
    }

    /**
     * Returns a new instance of {@link VirtualHostAnnotatedServiceBindingBuilder} to build
     * an annotated service fluently.
     */
    public VirtualHostAnnotatedServiceBindingBuilder annotatedService() {
        return new VirtualHostAnnotatedServiceBindingBuilder(this);
    }

    VirtualHostBuilder addServiceConfigSetters(ServiceConfigSetters serviceConfigSetters) {
        this.serviceConfigSetters.add(serviceConfigSetters);
        return this;
    }

    private List<ServiceConfigSetters> getServiceConfigSetters(
            @Nullable VirtualHostBuilder defaultVirtualHostBuilder) {
        final List<ServiceConfigSetters> serviceConfigSetters;
        if (defaultVirtualHostBuilder != null) {
            serviceConfigSetters = ImmutableList.<ServiceConfigSetters>builder()
                                                .addAll(this.serviceConfigSetters)
                                                .addAll(defaultVirtualHostBuilder.serviceConfigSetters)
                                                .build();
        } else {
            serviceConfigSetters = ImmutableList.copyOf(this.serviceConfigSetters);
        }
        return serviceConfigSetters;
    }

    VirtualHostBuilder addRouteDecoratingService(RouteDecoratingService routeDecoratingService) {
        routeDecoratingServices.add(0, routeDecoratingService);
        return this;
    }

    @Nullable
    private Function<? super HttpService, ? extends HttpService> getRouteDecoratingService(
            @Nullable VirtualHostBuilder defaultVirtualHostBuilder) {
        final List<RouteDecoratingService> routeDecoratingServices;
        if (defaultVirtualHostBuilder != null) {
            routeDecoratingServices = ImmutableList.<RouteDecoratingService>builder()
                    .addAll(this.routeDecoratingServices)
                    .addAll(defaultVirtualHostBuilder.routeDecoratingServices)
                    .build();
        } else {
            routeDecoratingServices = ImmutableList.copyOf(this.routeDecoratingServices);
        }

        if (!routeDecoratingServices.isEmpty()) {
            return RouteDecoratingService.newDecorator(
                    Routers.ofRouteDecoratingService(routeDecoratingServices));
        } else {
            return null;
        }
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}s
     */
    public VirtualHostBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.ofCatchAll(), decorator);
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@link DecoratingHttpServiceFunction}.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public VirtualHostBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.ofCatchAll(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public VirtualHostBuilder decorator(
            String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().path(pathPattern).build(), decoratingHttpServiceFunction);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     */
    public VirtualHostBuilder decorator(
            String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().path(pathPattern).build(), decorator);
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    public VirtualHostBuilder decorator(
            Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        requireNonNull(route, "route");
        requireNonNull(decorator, "decorator");
        return addRouteDecoratingService(new RouteDecoratingService(route, decorator));
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public VirtualHostBuilder decorator(
            Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        requireNonNull(decoratingHttpServiceFunction, "decoratingHttpServiceFunction");
        return decorator(route, delegate -> new FunctionalDecoratingHttpService(
                delegate, decoratingHttpServiceFunction));
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     */
    public VirtualHostBuilder decoratorUnder(
            String prefix, Function<? super HttpService, ? extends HttpService> decorator) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decorator);
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    public VirtualHostBuilder decoratorUnder(
            String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decoratingHttpServiceFunction);
    }

    /**
     * Sets the access logger mapper of this {@link VirtualHost}.
     */
    public VirtualHostBuilder accessLogger(Function<? super VirtualHost, ? extends Logger> mapper) {
        accessLoggerMapper = requireNonNull(mapper, "mapper");
        return this;
    }

    /**
     * Sets the {@link Logger} of this {@link VirtualHost}, which is used for writing access logs.
     */
    public VirtualHostBuilder accessLogger(Logger logger) {
        requireNonNull(logger, "logger");
        return accessLogger(host -> logger);
    }

    /**
     * Sets the {@link Logger} named {@code loggerName} of this {@link VirtualHost},
     * which is used for writing access logs.
     */
    public VirtualHostBuilder accessLogger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        return accessLogger(host -> LoggerFactory.getLogger(loggerName));
    }

    /**
     * Sets the {@link RejectedRouteHandler} which will be invoked when an attempt to bind
     * an {@link HttpService} at a certain {@link Route} is rejected. If not set,
     * the {@link RejectedRouteHandler} set via
     * {@link ServerBuilder#rejectedRouteHandler(RejectedRouteHandler)} is used.
     */
    public VirtualHostBuilder rejectedRouteHandler(RejectedRouteHandler handler) {
        rejectedRouteHandler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Sets the timeout of a request. If not set, the value set via
     * {@link ServerBuilder#requestTimeoutMillis(long)} is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public VirtualHostBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of a request in milliseconds. If not set, the value set via
     * {@link ServerBuilder#requestTimeoutMillis(long)} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public VirtualHostBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    /**
     * Sets the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request. If not set, the value set via
     * {@link ServerBuilder#maxRequestLength(long)} is used.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public VirtualHostBuilder maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return this;
    }

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the server responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the server responses will not expose such server-side details to the client.
     * If not set, the value set via {@link ServerBuilder#verboseResponses(boolean)} is used.
     */
    public VirtualHostBuilder verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    /**
     * Sets the access log writer of this {@link VirtualHost}. If not set, the {@link AccessLogWriter} set via
     * {@link ServerBuilder#accessLogWriter(AccessLogWriter, boolean)} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    public VirtualHostBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    /**
     * Sets the {@link RequestConverterFunction}s, {@link ResponseConverterFunction}
     * and {@link ExceptionHandlerFunction}s for creating an {@link AnnotatedServiceExtensions}.
     *
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     */
    public VirtualHostBuilder annotatedServiceExtensions(
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions) {
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        annotatedServiceExtensions =
                new AnnotatedServiceExtensions(ImmutableList.copyOf(requestConverterFunctions),
                                               ImmutableList.copyOf(responseConverterFunctions),
                                               ImmutableList.copyOf(exceptionHandlerFunctions));
        return this;
    }

    @Nullable
    AnnotatedServiceExtensions annotatedServiceExtensions() {
        return annotatedServiceExtensions;
    }

    /**
     * Returns a newly-created {@link VirtualHost} based on the properties of this builder and the services
     * added to this builder.
     */
    VirtualHost build(VirtualHostBuilder template) {
        requireNonNull(template, "template");

        if (defaultHostname == null) {
            if (hostnamePattern != null) {
                defaultHostname = hostnamePattern.startsWith("*.") ? hostnamePattern.substring(2)
                                                                   : hostnamePattern;
            } else {
                defaultHostname = SystemInfo.hostname();
            }
        }

        if (hostnamePattern == null) {
            hostnamePattern = defaultVirtualHost ? "*" : "*." + defaultHostname;
        }

        ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);

        // Retrieve all settings as a local copy. Use default builder's properties if not set.
        final long requestTimeoutMillis =
                this.requestTimeoutMillis != null ?
                this.requestTimeoutMillis : template.requestTimeoutMillis;
        final long maxRequestLength =
                this.maxRequestLength != null ?
                this.maxRequestLength : template.maxRequestLength;
        final boolean verboseResponses =
                this.verboseResponses != null ?
                this.verboseResponses : template.verboseResponses;
        final RejectedRouteHandler rejectedRouteHandler =
                this.rejectedRouteHandler != null ?
                this.rejectedRouteHandler : template.rejectedRouteHandler;

        final AccessLogWriter accessLogWriter;
        final boolean shutdownAccessLogWriterOnStop;
        if (this.accessLogWriter != null) {
            accessLogWriter = this.accessLogWriter;
            shutdownAccessLogWriterOnStop = this.shutdownAccessLogWriterOnStop;
        } else {
            accessLogWriter = template.accessLogWriter;
            shutdownAccessLogWriterOnStop = template.shutdownAccessLogWriterOnStop;
        }

        final Function<? super VirtualHost, ? extends Logger> accessLoggerMapper =
                this.accessLoggerMapper != null ?
                this.accessLoggerMapper : template.accessLoggerMapper;

        final AnnotatedServiceExtensions extensions =
                annotatedServiceExtensions != null ?
                annotatedServiceExtensions : template.annotatedServiceExtensions;

        assert rejectedRouteHandler != null;
        assert accessLogWriter != null;
        assert accessLoggerMapper != null;
        assert extensions != null;

        final List<ServiceConfig> serviceConfigs = getServiceConfigSetters(template)
                .stream()
                .flatMap(cfgSetters -> {
                    if (cfgSetters instanceof VirtualHostAnnotatedServiceBindingBuilder) {
                        return ((VirtualHostAnnotatedServiceBindingBuilder) cfgSetters)
                                .buildServiceConfigBuilder(extensions).stream();
                    } else if (cfgSetters instanceof AnnotatedServiceBindingBuilder) {
                        return ((AnnotatedServiceBindingBuilder) cfgSetters)
                                .buildServiceConfigBuilder(extensions).stream();
                    } else if (cfgSetters instanceof ServiceConfigBuilder) {
                        return Stream.of((ServiceConfigBuilder) cfgSetters);
                    } else {
                        // Should not reach here.
                        throw new Error("Unexpected service config setters type: " +
                                        cfgSetters.getClass().getSimpleName());
                    }
                }).map(cfgBuilder -> {
                    return cfgBuilder.build(requestTimeoutMillis, maxRequestLength, verboseResponses,
                                            accessLogWriter, shutdownAccessLogWriterOnStop);
                }).collect(toImmutableList());

        final ServiceConfig fallbackServiceConfig =
                new ServiceConfigBuilder(Route.ofCatchAll(), FallbackService.INSTANCE)
                        .build(requestTimeoutMillis, maxRequestLength, verboseResponses,
                               accessLogWriter, shutdownAccessLogWriterOnStop);

        SslContext sslContext = null;
        boolean releaseSslContextOnFailure = false;
        try {
            // Whether the `SslContext` came (or was created) from this `VirtualHost`'s properties.
            boolean sslContextFromThis = false;

            // Build a new SslContext or use a user-specified one for backward compatibility.
            if (sslContextBuilderSupplier != null) {
                sslContext = buildSslContext(sslContextBuilderSupplier, tlsCustomizers);
                sslContextFromThis = true;
                releaseSslContextOnFailure = true;
            } else if (template.sslContextBuilderSupplier != null) {
                sslContext = buildSslContext(template.sslContextBuilderSupplier, template.tlsCustomizers);
                releaseSslContextOnFailure = true;
            }

            // Generate a self-signed certificate if necessary.
            if (sslContext == null) {
                final boolean tlsSelfSigned;
                final List<Consumer<? super SslContextBuilder>> tlsCustomizers;
                if (this.tlsSelfSigned != null) {
                    tlsSelfSigned = this.tlsSelfSigned;
                    tlsCustomizers = this.tlsCustomizers;
                    sslContextFromThis = true;
                } else {
                    tlsSelfSigned = template.tlsSelfSigned;
                    tlsCustomizers = template.tlsCustomizers;
                }

                if (tlsSelfSigned) {
                    try {
                        final SelfSignedCertificate ssc = selfSignedCertificate();
                        sslContext = buildSslContext(() -> SslContextBuilder.forServer(ssc.certificate(),
                                                                                       ssc.privateKey()),
                                                     tlsCustomizers);
                        releaseSslContextOnFailure = true;
                    } catch (Exception e) {
                        throw new RuntimeException("failed to create a self signed certificate", e);
                    }
                }
            }

            // Reject if a user called `tlsCustomizer()` without `tls()` or `tlsSelfSigned()`.
            checkState(sslContextFromThis || tlsCustomizers.isEmpty(),
                       "Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");

            // Validate the built `SslContext`.
            if (sslContext != null) {
                validateSslContext(sslContext);
                checkState(sslContext.isServer(), "sslContextBuilder built a client SSL context.");
            }

            final VirtualHost virtualHost =
                    new VirtualHost(defaultHostname, hostnamePattern, sslContext,
                                    serviceConfigs, fallbackServiceConfig, rejectedRouteHandler,
                                    accessLoggerMapper, requestTimeoutMillis, maxRequestLength,
                                    verboseResponses, accessLogWriter, shutdownAccessLogWriterOnStop);

            final Function<? super HttpService, ? extends HttpService> decorator =
                    getRouteDecoratingService(template);
            final VirtualHost decoratedVirtualHost = decorator != null ? virtualHost.decorate(decorator)
                                                                       : virtualHost;
            releaseSslContextOnFailure = false;
            return decoratedVirtualHost;
        } finally {
            if (releaseSslContextOnFailure) {
                ReferenceCountUtil.release(sslContext);
            }
        }
    }

    private SelfSignedCertificate selfSignedCertificate() throws CertificateException {
        if (selfSignedCertificate == null) {
            return selfSignedCertificate = new SelfSignedCertificate(defaultHostname);
        }
        return selfSignedCertificate;
    }

    private static SslContext buildSslContext(
            Supplier<SslContextBuilder> sslContextBuilderSupplier,
            Iterable<? extends Consumer<? super SslContextBuilder>> tlsCustomizers) {
        return SslContextUtil.createSslContext(sslContextBuilderSupplier, false, tlsCustomizers);
    }

    /**
     * Makes sure the specified {@link SslContext} is configured properly. If configured as client context or
     * key store password is not given to key store when {@link SslContext} was created using
     * {@link KeyManagerFactory}, the validation will fail and an {@link IllegalStateException} will be raised.
     */
    private static SslContext validateSslContext(SslContext sslContext) {
        if (!sslContext.isServer()) {
            throw new IllegalArgumentException("sslContext: " + sslContext + " (expected: server context)");
        }

        SSLEngine serverEngine = null;
        SSLEngine clientEngine = null;

        try {
            serverEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
            serverEngine.setUseClientMode(false);
            serverEngine.setNeedClientAuth(false);

            final SslContext sslContextClient =
                    buildSslContext(SslContextBuilder::forClient, ImmutableList.of());
            clientEngine = sslContextClient.newEngine(ByteBufAllocator.DEFAULT);
            clientEngine.setUseClientMode(true);

            final ByteBuffer appBuf = ByteBuffer.allocate(clientEngine.getSession().getApplicationBufferSize());
            final ByteBuffer packetBuf = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());

            clientEngine.wrap(appBuf, packetBuf);
            appBuf.clear();
            packetBuf.flip();
            serverEngine.unwrap(packetBuf, appBuf);
        } catch (SSLException e) {
            throw new IllegalStateException("failed to validate SSL/TLS configuration: " + e.getMessage(), e);
        } finally {
            ReferenceCountUtil.release(serverEngine);
            ReferenceCountUtil.release(clientEngine);
        }

        return sslContext;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("defaultHostname", defaultHostname)
                          .add("hostnamePattern", hostnamePattern)
                          .add("serviceConfigSetters", serviceConfigSetters)
                          .add("routeDecoratingServices", routeDecoratingServices)
                          .add("accessLoggerMapper", accessLoggerMapper)
                          .add("rejectedRouteHandler", rejectedRouteHandler)
                          .add("requestTimeoutMillis", requestTimeoutMillis)
                          .add("maxRequestLength", maxRequestLength)
                          .add("verboseResponses", verboseResponses)
                          .add("accessLogWriter", accessLogWriter)
                          .add("shutdownAccessLogWriterOnStop", shutdownAccessLogWriterOnStop)
                          .toString();
    }
}
