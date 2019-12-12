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

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.server.ServiceConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServiceConfig.validateRequestTimeoutMillis;
import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.SslContextUtil;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpService;
import com.linecorp.armeria.internal.crypto.BouncyCastleKeyFactoryProvider;
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

    /**
     * Validate {@code sslContext} is configured properly. If {@code sslContext} is configured as client
     * context, or key store password is not given to key store when {@code sslContext} is created using key
     * manager factory, the validation will fail and an {@link SSLException} will be raised.
     */
    private static SslContext validateSslContext(SslContext sslContext) throws SSLException {
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
                    buildSslContext(SslContextBuilder::forClient, sslContextBuilder -> {});
            clientEngine = sslContextClient.newEngine(ByteBufAllocator.DEFAULT);
            clientEngine.setUseClientMode(true);

            final ByteBuffer appBuf = ByteBuffer.allocate(clientEngine.getSession().getApplicationBufferSize());
            final ByteBuffer packetBuf = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());

            clientEngine.wrap(appBuf, packetBuf);
            appBuf.clear();
            packetBuf.flip();
            serverEngine.unwrap(packetBuf, appBuf);
        } catch (SSLException e) {
            throw new SSLException("failed to validate SSL/TLS configuration: " + e.getMessage(), e);
        } finally {
            ReferenceCountUtil.release(serverEngine);
            ReferenceCountUtil.release(clientEngine);
        }

        return sslContext;
    }

    private final ServerBuilder serverBuilder;
    private final boolean defaultVirtualHost;
    private final List<ServiceConfigBuilder> serviceConfigBuilders = new ArrayList<>();
    private final List<VirtualHostAnnotatedServiceBindingBuilder> virtualHostAnnotatedServiceBindingBuilders =
            new ArrayList<>();

    @Nullable
    private String defaultHostname;
    private String hostnamePattern = "*";
    @Nullable
    private SslContext sslContext;
    @Nullable
    private Boolean tlsSelfSigned;
    private final List<RouteDecoratingService> routeDecoratingServices = new ArrayList<>();
    @Nullable
    private Function<VirtualHost, Logger> accessLoggerMapper;

    @Nullable
    private RejectedRouteHandler rejectedRouteHandler;
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;
    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;
    @Nullable
    private AccessLogWriter accessLogWriter;
    private boolean shutdownAccessLogWriterOnStop;
    private AnnotatedHttpServiceExtensions annotatedHttpServiceExtensions =
            new AnnotatedHttpServiceExtensions(ImmutableList.of(), ImmutableList.of(), ImmutableList.of());

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
        this.hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@link SslContext}.
     */
    public VirtualHostBuilder tls(SslContext sslContext) throws SSLException {
        checkState(this.sslContext == null, "sslContext is already set: %s", this.sslContext);

        this.sslContext = validateSslContext(requireNonNull(sslContext, "sslContext"));
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile) throws SSLException {
        tls(keyCertChainFile, keyFile, null, sslContextBuilder -> { /* noop */ });
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * cleartext {@code keyFile} and {@code tlsCustomizer}.
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile,
                                  Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        tls(keyCertChainFile, keyFile, null, tlsCustomizer);
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile,
                                  @Nullable String keyPassword) throws SSLException {
        tls(keyCertChainFile, keyFile, keyPassword, sslContextBuilder -> { /* noop */ });
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile}, {@code keyPassword} and {@code tlsCustomizer}.
     */
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword,
                                  Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        requireNonNull(keyFile, "keyFile");
        requireNonNull(tlsCustomizer, "tlsCustomizer");

        if (!keyCertChainFile.exists()) {
            throw new SSLException("non-existent certificate chain file: " + keyCertChainFile);
        }
        if (!keyCertChainFile.canRead()) {
            throw new SSLException("cannot read certificate chain file: " + keyCertChainFile);
        }
        if (!keyFile.exists()) {
            throw new SSLException("non-existent key file: " + keyFile);
        }
        if (!keyFile.canRead()) {
            throw new SSLException("cannot read key file: " + keyFile);
        }

        tls(buildSslContext(() -> SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword),
                            tlsCustomizer));
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyManagerFactory}
     * and {@code tlsCustomizer}.
     */
    public VirtualHostBuilder tls(KeyManagerFactory keyManagerFactory,
                                  Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        requireNonNull(keyManagerFactory, "keyManagerFactory");
        requireNonNull(tlsCustomizer, "tlsCustomizer");

        tls(buildSslContext(() -> SslContextBuilder.forServer(keyManagerFactory), tlsCustomizer));
        return this;
    }

    private static SslContext buildSslContext(
            Supplier<SslContextBuilder> builderSupplier,
            Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        try {
            return BouncyCastleKeyFactoryProvider.call(() -> SslContextUtil.createSslContext(
                    builderSupplier, false, tlsCustomizer));
        } catch (RuntimeException | SSLException e) {
            throw e;
        } catch (Exception e) {
            throw new SSLException("failed to configure TLS: " + e, e);
        }
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     */
    public VirtualHostBuilder tlsSelfSigned() {
        tlsSelfSigned = true;
        return this;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     */
    public VirtualHostBuilder tlsSelfSigned(boolean tlsSelfSigned) {
        this.tlsSelfSigned = tlsSelfSigned;
        return this;
    }

    /**
     * Configures an {@link HttpService} of the {@link VirtualHost} with the {@code customizer}.
     */
    public VirtualHostBuilder withRoute(Consumer<VirtualHostServiceBindingBuilder> customizer) {
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
     * Binds the specified {@link HttpService} at the specified path pattern.
     *
     * @deprecated Use {@link #service(String, HttpService)} instead.
     */
    @Deprecated
    public VirtualHostBuilder serviceAt(String pathPattern, HttpService service) {
        return service(pathPattern, service);
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
        serviceConfigBuilders.add(new ServiceConfigBuilder(route, service));
        return this;
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
            Iterable<Function<? super HttpService, ? extends HttpService>> decorators) {
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
        final AnnotatedHttpServiceExtensions configurator =
                AnnotatedHttpServiceExtensions
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
     * {@link AnnotatedHttpService} fluently.
     */
    public VirtualHostAnnotatedServiceBindingBuilder annotatedService() {
        return new VirtualHostAnnotatedServiceBindingBuilder(this);
    }

    VirtualHostBuilder addServiceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serviceConfigBuilders.add(serviceConfigBuilder);
        return this;
    }

    VirtualHostBuilder addAnnotatedServiceBindingBuilder(
            VirtualHostAnnotatedServiceBindingBuilder virtualHostAnnotatedServiceBindingBuilder) {
        virtualHostAnnotatedServiceBindingBuilders.add(virtualHostAnnotatedServiceBindingBuilder);
        return this;
    }

    private List<ServiceConfigBuilder> getServiceConfigBuilders(
            @Nullable VirtualHostBuilder defaultVirtualHostBuilder) {
        final List<ServiceConfigBuilder> serviceConfigBuilders;
        if (defaultVirtualHostBuilder != null) {
            serviceConfigBuilders = ImmutableList.<ServiceConfigBuilder>builder()
                                                 .addAll(this.serviceConfigBuilders)
                                                 .addAll(defaultVirtualHostBuilder.serviceConfigBuilders)
                                                 .build();
        } else {
            serviceConfigBuilders = ImmutableList.copyOf(this.serviceConfigBuilders);
        }
        return serviceConfigBuilders;
    }

    VirtualHostBuilder addRouteDecoratingService(RouteDecoratingService routeDecoratingService) {
        routeDecoratingServices.add(routeDecoratingService);
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
    public VirtualHostBuilder accessLogger(Function<VirtualHost, Logger> mapper) {
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
     * Sets the {@link ContentPreviewerFactory} for a request of this {@link VirtualHost}. If not set,
     * the {@link ContentPreviewerFactory} ser via
     * {@link ServerBuilder#requestContentPreviewerFactory(ContentPreviewerFactory)} is used.
     */
    public VirtualHostBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a response of this {@link VirtualHost}. If not set,
     * the {@link ContentPreviewerFactory} set via
     * {@link ServerBuilder#responseContentPreviewerFactory(ContentPreviewerFactory)} is used.
     */
    public VirtualHostBuilder responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response of this {@link VirtualHost}.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    public VirtualHostBuilder contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for a request and a response of this {@link VirtualHost}.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public VirtualHostBuilder contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request and a response of this {@link VirtualHost}.
     */
    public VirtualHostBuilder contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
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
     * FIXME(heowc): Update javadoc.
     */
    public VirtualHostBuilder annotatedHttpServiceExtensions(
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        requireNonNull(exceptionHandlerFunctions, "exceptionHandlerFunctions");
        requireNonNull(requestConverterFunctions, "requestConverterFunctions");
        requireNonNull(responseConverterFunctions, "responseConverterFunctions");
        annotatedHttpServiceExtensions =
                new AnnotatedHttpServiceExtensions(ImmutableList.copyOf(exceptionHandlerFunctions),
                                                   ImmutableList.copyOf(requestConverterFunctions),
                                                   ImmutableList.copyOf(responseConverterFunctions));
        return this;
    }

    /**
     * Returns a newly-created {@link VirtualHost} based on the properties of this builder and the services
     * added to this builder.
     */
    VirtualHost build(VirtualHostBuilder template) {
        requireNonNull(template, "template");

        if (defaultHostname == null) {
            if ("*".equals(hostnamePattern)) {
                defaultHostname = SystemInfo.hostname();
            } else if (hostnamePattern.startsWith("*.")) {
                defaultHostname = hostnamePattern.substring(2);
            } else {
                defaultHostname = hostnamePattern;
            }
        } else {
            ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);
        }

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
        final ContentPreviewerFactory requestContentPreviewerFactory =
                this.requestContentPreviewerFactory != null ?
                this.requestContentPreviewerFactory : template.requestContentPreviewerFactory;
        final ContentPreviewerFactory responseContentPreviewerFactory =
                this.responseContentPreviewerFactory != null ?
                this.responseContentPreviewerFactory : template.responseContentPreviewerFactory;
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

        final Function<VirtualHost, Logger> accessLoggerMapper =
                this.accessLoggerMapper != null ?
                this.accessLoggerMapper : template.accessLoggerMapper;

        assert requestContentPreviewerFactory != null;
        assert responseContentPreviewerFactory != null;
        assert rejectedRouteHandler != null;
        assert accessLogWriter != null;
        assert accessLoggerMapper != null;

        virtualHostAnnotatedServiceBindingBuilders
                .forEach(builder -> builder.applyToServiceConfigBuilder(annotatedHttpServiceExtensions));

        final List<ServiceConfigBuilder> serviceConfigBuilders =
                getServiceConfigBuilders(template);
        final List<ServiceConfig> serviceConfigs = serviceConfigBuilders.stream().map(cfgBuilder -> {
            return cfgBuilder.build(requestTimeoutMillis, maxRequestLength, verboseResponses,
                                    requestContentPreviewerFactory, responseContentPreviewerFactory,
                                    accessLogWriter, shutdownAccessLogWriterOnStop);
        }).collect(toImmutableList());

        final ServiceConfig fallbackServiceConfig =
                new ServiceConfigBuilder(Route.ofCatchAll(), FallbackService.INSTANCE)
                        .build(requestTimeoutMillis, maxRequestLength, verboseResponses,
                               requestContentPreviewerFactory, responseContentPreviewerFactory,
                               accessLogWriter, shutdownAccessLogWriterOnStop);

        SslContext sslContext = this.sslContext != null ? this.sslContext : template.sslContext;
        final boolean tlsSelfSigned = this.tlsSelfSigned != null ? this.tlsSelfSigned : template.tlsSelfSigned;
        if (sslContext == null && tlsSelfSigned) {
            try {
                final SelfSignedCertificate ssc = new SelfSignedCertificate(defaultHostname);
                tls(ssc.certificate(), ssc.privateKey());
                sslContext = this.sslContext;
            } catch (Exception e) {
                throw new RuntimeException("failed to create a self signed certificate", e);
            }
        }

        final VirtualHost virtualHost =
                new VirtualHost(defaultHostname, hostnamePattern, sslContext,
                                serviceConfigs, fallbackServiceConfig, rejectedRouteHandler,
                                accessLoggerMapper, requestTimeoutMillis, maxRequestLength,
                                verboseResponses, requestContentPreviewerFactory,
                                responseContentPreviewerFactory, accessLogWriter,
                                shutdownAccessLogWriterOnStop);

        final Function<? super HttpService, ? extends HttpService> decorator =
                getRouteDecoratingService(template);
        return decorator != null ? virtualHost.decorate(decorator) : virtualHost;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("defaultHostname", defaultHostname)
                          .add("hostnamePattern", hostnamePattern)
                          .add("serviceConfigBuilders", serviceConfigBuilders)
                          .add("sslContext", sslContext)
                          .add("tlsSelfSigned", tlsSelfSigned)
                          .add("routeDecoratingServices", routeDecoratingServices)
                          .add("accessLoggerMapper", accessLoggerMapper)
                          .add("rejectedRouteHandler", rejectedRouteHandler)
                          .add("requestTimeoutMillis", requestTimeoutMillis)
                          .add("maxRequestLength", maxRequestLength)
                          .add("verboseResponses", verboseResponses)
                          .add("requestContentPreviewerFactory", requestContentPreviewerFactory)
                          .add("responseContentPreviewerFactory", responseContentPreviewerFactory)
                          .add("accessLogWriter", accessLogWriter)
                          .add("shutdownAccessLogWriterOnStop", shutdownAccessLogWriterOnStop)
                          .toString();
    }
}
