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
import static com.linecorp.armeria.server.ServerConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateRequestTimeoutMillis;
import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
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

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.SslContextUtil;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.internal.crypto.BouncyCastleKeyFactoryProvider;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
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

    private static final ApplicationProtocolConfig HTTPS_ALPN_CFG = new ApplicationProtocolConfig(
            Protocol.ALPN,
            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
            SelectorFailureBehavior.NO_ADVERTISE,
            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1);

    private static SslContext validateSslContext(@Nullable SslContext sslContext) throws SSLException {
        if (sslContext != null && !sslContext.isServer()) {
            throw new IllegalArgumentException("sslContext: " + sslContext + " (expected: server context)");
        }

        SSLEngine serverEngine = null;
        SSLEngine clientEngine = null;

        try {
            serverEngine = sslContext.newEngine(ByteBufAllocator.DEFAULT);
            serverEngine.setUseClientMode(false);
            serverEngine.setNeedClientAuth(false);

            final SslContext sslContextClient =
                    VirtualHostBuilder.buildSslContext(SslContextBuilder::forClient, sslContextBuilder -> {});
            clientEngine = sslContextClient.newEngine(ByteBufAllocator.DEFAULT);
            clientEngine.setUseClientMode(true);

            clientEngine.beginHandshake();
            serverEngine.beginHandshake();

            final ByteBuffer appBuf = ByteBuffer.allocate(clientEngine.getSession().getApplicationBufferSize());
            final ByteBuffer packetBuf = ByteBuffer.allocate(clientEngine.getSession().getPacketBufferSize());

            clientEngine.wrap(appBuf, packetBuf);
            appBuf.clear();
            packetBuf.flip();
            serverEngine.unwrap(packetBuf, appBuf);
        } catch (SSLException e) {
            throw new SSLException("failed to validate SSL/TLS configuration: " + e.getMessage());
        } finally {
            ReferenceCountUtil.release(serverEngine);
            ReferenceCountUtil.release(clientEngine);
        }

        return sslContext;
    }
    
    private final ServerBuilder serverBuilder;
    private final boolean defaultVirtualHost;
    private final List<ServiceConfigBuilder> serviceConfigBuilders = new ArrayList<>();

    @Nullable
    private String defaultHostname;
    private String hostnamePattern = "*";
    @Nullable
    private SslContext sslContext;
    private boolean tlsSelfSigned;
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

    static SslContext buildSslContext(
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
     *
     * @throws CertificateException if failed to generate a self-signed certificate
     */
    public VirtualHostBuilder tlsSelfSigned() throws SSLException, CertificateException {
        tlsSelfSigned = true;
        return this;
    }

    /**
     * Configures a {@link Service} of the {@link VirtualHost} with the {@code customizer}.
     */
    public VirtualHostBuilder withRoute(Consumer<VirtualHostServiceBindingBuilder> customizer) {
        final VirtualHostServiceBindingBuilder builder = new VirtualHostServiceBindingBuilder(this);
        customizer.accept(builder);
        return this;
    }

    /**
     * Returns a {@link ServiceBindingBuilder} which is for binding a {@link Service} fluently.
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
     * Binds the specified {@link Service} at the specified path pattern.
     *
     * @deprecated Use {@link #service(String, Service)} instead.
     */
    @Deprecated
    public VirtualHostBuilder serviceAt(String pathPattern,
                                        Service<HttpRequest, HttpResponse> service) {
        return service(pathPattern, service);
    }

    /**
     * Binds the specified {@link Service} under the specified directory.
     */
    public VirtualHostBuilder serviceUnder(String pathPrefix,
                                           Service<HttpRequest, HttpResponse> service) {
        service(Route.builder().pathPrefix(pathPrefix).build(), service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified path pattern. e.g.
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
    public VirtualHostBuilder service(String pathPattern,
                                      Service<HttpRequest, HttpResponse> service) {
        service(Route.builder().path(pathPattern).build(), service);
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link Route}.
     */
    public VirtualHostBuilder service(Route route,
                                      Service<HttpRequest, HttpResponse> service) {
        serviceConfigBuilders.add(new ServiceConfigBuilder(route, service));
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link Route}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    VirtualHostBuilder service(Route route, Service<HttpRequest, HttpResponse> service,
                               String loggerName) {
        serviceConfigBuilders.add(new ServiceConfigBuilder(route, service).loggerName(loggerName));
        return this;
    }

    /**
     * Decorates and binds the specified {@link ServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link ServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    public VirtualHostBuilder service(
            ServiceWithRoutes<HttpRequest, HttpResponse> serviceWithRoutes,
            Iterable<Function<? super Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>>> decorators) {
        requireNonNull(serviceWithRoutes, "serviceWithRoutes");
        requireNonNull(serviceWithRoutes.routes(), "serviceWithRoutes.routes()");
        requireNonNull(decorators, "decorators");

        Service<HttpRequest, HttpResponse> decorated = serviceWithRoutes;
        for (Function<? super Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> d : decorators) {
            checkNotNull(d, "decorators contains null: %s", decorators);
            decorated = d.apply(decorated);
            checkNotNull(decorated, "A decorator returned null: %s", d);
        }

        final Service<HttpRequest, HttpResponse> finalDecorated = decorated;
        serviceWithRoutes.routes().forEach(route -> service(route, finalDecorated));
        return this;
    }

    /**
     * Decorates and binds the specified {@link ServiceWithRoutes} at multiple {@link Route}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithRoutes the {@link ServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @SafeVarargs
    public final VirtualHostBuilder service(
            ServiceWithRoutes<HttpRequest, HttpResponse> serviceWithRoutes,
            Function<? super Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>>... decorators) {
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public VirtualHostBuilder annotatedService(
            Object service,
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator,
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
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
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service,
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator,
            Object... exceptionHandlersAndConverters) {
        return annotatedService(pathPrefix, service, decorator,
                                ImmutableList.copyOf(requireNonNull(exceptionHandlersAndConverters,
                                                                    "exceptionHandlersAndConverters")));
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters an iterable object of {@link ExceptionHandlerFunction},
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
     * @param exceptionHandlersAndConverters an iterable object of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service,
            Function<Service<HttpRequest, HttpResponse>,
                    ? extends Service<HttpRequest, HttpResponse>> decorator,
            Iterable<?> exceptionHandlersAndConverters) {
        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(decorator, "decorator");
        requireNonNull(exceptionHandlersAndConverters, "exceptionHandlersAndConverters");

        final List<AnnotatedHttpServiceElement> elements =
                AnnotatedHttpServiceFactory.find(pathPrefix, service, exceptionHandlersAndConverters);
        elements.forEach(e -> {
            Service<HttpRequest, HttpResponse> s = e.service();
            // Apply decorators which are specified in the service class.
            s = e.decorator().apply(s);
            // Apply decorators which are passed via annotatedService() methods.
            s = decorator.apply(s);

            // If there is a decorator, we should add one more decorator which handles an exception
            // raised from decorators.
            if (s != e.service()) {
                s = e.service().exceptionHandlingDecorator().apply(s);
            }
            service(e.route(), s);
        });
        return this;
    }

    VirtualHostBuilder serviceConfigBuilder(ServiceConfigBuilder serviceConfigBuilder) {
        serviceConfigBuilders.add(serviceConfigBuilder);
        return this;
    }

    VirtualHostBuilder serviceConfigBuilders(List<ServiceConfigBuilder> serviceConfigBuilders) {
        this.serviceConfigBuilders.addAll(serviceConfigBuilders);
        return this;
    }

    List<ServiceConfigBuilder> serviceConfigBuilders() {
        return serviceConfigBuilders;
    }

    VirtualHostBuilder routeDecoratingService(RouteDecoratingService routeDecoratingService) {
        routeDecoratingServices.add(routeDecoratingService);
        return this;
    }

    VirtualHostBuilder routeDecoratingServices(List<RouteDecoratingService> routeDecoratingServices) {
        this.routeDecoratingServices.addAll(routeDecoratingServices);
        return this;
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostBuilder decorator(Function<T, R> decorator) {
        return decorator(Route.builder().catchAll().build(), decorator);
    }

    /**
     * Decorates all {@link Service}s with the specified {@link DecoratingServiceFunction}.
     *
     * @param decoratingServiceFunction the {@link DecoratingServiceFunction} that decorates a {@link Service}
     */
    public VirtualHostBuilder decorator(
            DecoratingServiceFunction<HttpRequest, HttpResponse> decoratingServiceFunction) {
        return decorator(Route.builder().catchAll().build(), decoratingServiceFunction);
    }

    /**
     * Decorates {@link Service}s whose {@link Route} matches the specified {@code pathPattern}.
     *
     * @param decoratingServiceFunction the {@link DecoratingServiceFunction} that decorates a {@link Service}
     */
    public VirtualHostBuilder decorator(
            String pathPattern,
            DecoratingServiceFunction<HttpRequest, HttpResponse> decoratingServiceFunction) {
        return decorator(Route.builder().path(pathPattern).build(), decoratingServiceFunction);
    }

    /**
     * Decorates {@link Service}s whose {@link Route} matches the specified {@code pathPattern}.
     *
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostBuilder decorator(String pathPattern, Function<T, R> decorator) {
        return decorator(Route.builder().path(pathPattern).build(), decorator);
    }

    /**
     * Decorates {@link Service}s whose {@link Route} matches the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decorator the {@link Function} that decorates {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostBuilder decorator(Route route, Function<T, R> decorator) {
        requireNonNull(route, "route");
        requireNonNull(decorator, "decorator");
        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;
        return routeDecoratingService(new RouteDecoratingService(route, castDecorator));
    }

    /**
     * Decorates {@link Service}s whose {@link Route} matches the specified {@link Route}.
     *
     * @param route the route being decorated
     * @param decoratingServiceFunction the {@link DecoratingServiceFunction} that decorates {@link Service}
     */
    public VirtualHostBuilder decorator(
            Route route,
            DecoratingServiceFunction<HttpRequest, HttpResponse> decoratingServiceFunction) {
        requireNonNull(decoratingServiceFunction, "decoratingServiceFunction");
        return decorator(route,
                         delegate -> new FunctionalDecoratingService<>(delegate, decoratingServiceFunction));
    }

    /**
     * Decorates {@link Service}s under the specified directory.
     *
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    VirtualHostBuilder decoratorUnder(String prefix, Function<T, R> decorator) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decorator);
    }

    /**
     * Decorates {@link Service}s under the specified directory.
     *
     * @param decoratingServiceFunction the {@link DecoratingServiceFunction} that decorates a {@link Service}
     */
    public VirtualHostBuilder decoratorUnder(
            String prefix,
            DecoratingServiceFunction<HttpRequest, HttpResponse> decoratingServiceFunction) {
        return decorator(Route.builder().pathPrefix(prefix).build(), decoratingServiceFunction);
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
     * a {@link Service} at a certain {@link Route} is rejected. If not set,
     * {@link ServerBuilder#rejectedRouteHandler()} is used.
     */
    public VirtualHostBuilder rejectedRouteHandler(RejectedRouteHandler handler) {
        rejectedRouteHandler = requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Sets the timeout of a request. If not set, {@link ServerBuilder#requestTimeoutMillis()}
     * is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    public VirtualHostBuilder requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of a request in milliseconds. If not set,
     * {@link ServerBuilder#requestTimeoutMillis()} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public VirtualHostBuilder requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    /**
     * Sets the maximum allowed length of the content decoded at the session layer.
     * e.g. the content length of an HTTP request. If not set, {@link ServerBuilder#maxRequestLength()}
     * is used.
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
     * If not set, {@link ServerBuilder#verboseResponses()} is used.
     */
    public VirtualHostBuilder verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request of this {@link VirtualHost}. If not set,
     * {@link ServerBuilder#requestContentPreviewerFactory()} is used.
     */
    public VirtualHostBuilder requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a response of this {@link VirtualHost}. If not set,
     * {@link ServerBuilder#responseContentPreviewerFactory()} is used.
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
     * Sets the access log writer of this {@link VirtualHost}. If not set,
     * {@link ServerBuilder#accessLogWriter()} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    public VirtualHostBuilder accessLogWriter(AccessLogWriter accessLogWriter, boolean shutdownOnStop) {
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    /**
     * Returns a newly-created {@link VirtualHost} based on the properties of this builder and the services
     * added to this builder.
     */
    VirtualHost build() {
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

        final long requestTimeout = requestTimeoutMillis != null ? requestTimeoutMillis
                                                                 : serverBuilder.requestTimeoutMillis();
        final long maxRequest = maxRequestLength != null ? maxRequestLength
                                                         : serverBuilder.maxRequestLength();
        final boolean verboseResponses = this.verboseResponses != null ? this.verboseResponses
                                                                       : serverBuilder.verboseResponses();
        final ContentPreviewerFactory requestContentPreviewerFactory =
                this.requestContentPreviewerFactory != null ?
                this.requestContentPreviewerFactory : serverBuilder.requestContentPreviewerFactory();
        final ContentPreviewerFactory responseContentPreviewerFactory =
                this.responseContentPreviewerFactory != null ?
                this.responseContentPreviewerFactory : serverBuilder.responseContentPreviewerFactory();
        final RejectedRouteHandler rejectedRouteHandler =
                this.rejectedRouteHandler != null ?
                this.rejectedRouteHandler : serverBuilder.rejectedRouteHandler();

        final AccessLogWriter accessLogWriter;
        final boolean shutdownAccessLogWriterOnStop;
        if (this.accessLogWriter != null) {
            accessLogWriter = this.accessLogWriter;
            shutdownAccessLogWriterOnStop = this.shutdownAccessLogWriterOnStop;
        } else {
            accessLogWriter = serverBuilder.accessLogWriter();
            shutdownAccessLogWriterOnStop = serverBuilder.shutdownAccessLogWriterOnStop();
        }

        final List<ServiceConfig> serviceConfigs = serviceConfigBuilders.stream().map(cfgBuilder -> {
            if (cfgBuilder.requestTimeoutMillis() == null) {
                cfgBuilder.requestTimeoutMillis(requestTimeout);
            }
            if (cfgBuilder.maxRequestLength() == null) {
                cfgBuilder.maxRequestLength(maxRequest);
            }
            if (cfgBuilder.verboseResponses() == null) {
                cfgBuilder.verboseResponses(verboseResponses);
            }
            if (cfgBuilder.requestContentPreviewerFactory() == null) {
                cfgBuilder.requestContentPreviewerFactory(requestContentPreviewerFactory);
            }
            if (cfgBuilder.responseContentPreviewerFactory() == null) {
                cfgBuilder.responseContentPreviewerFactory(responseContentPreviewerFactory);
            }
            if (cfgBuilder.accessLogWriter() == null) {
                cfgBuilder.accessLogWriter(accessLogWriter, shutdownAccessLogWriterOnStop);
            }

            return cfgBuilder.build();
        }).collect(toImmutableList());

        if (sslContext == null && tlsSelfSigned) {
            try {
                final SelfSignedCertificate ssc = new SelfSignedCertificate(defaultHostname);
                tls(ssc.certificate(), ssc.privateKey());
            } catch (Exception e) {
                throw new RuntimeException("failed to create a self signed certificate", e);
            }
        }

        final Function<VirtualHost, Logger> accessLoggerMapper =
                this.accessLoggerMapper != null ? this.accessLoggerMapper : serverBuilder.accessLoggerMapper();

        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;
        if (!routeDecoratingServices.isEmpty()) {
            decorator = RouteDecoratingService.newDecorator(
                    Routers.ofRouteDecoratingService(routeDecoratingServices));
        } else {
            decorator = null;
        }

        final VirtualHost virtualHost =
                new VirtualHost(defaultHostname, hostnamePattern, sslContext, serviceConfigs,
                                rejectedRouteHandler,
                                accessLoggerMapper, requestTimeout, maxRequest,
                                verboseResponses, requestContentPreviewerFactory,
                                responseContentPreviewerFactory, accessLogWriter,
                                shutdownAccessLogWriterOnStop);
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
