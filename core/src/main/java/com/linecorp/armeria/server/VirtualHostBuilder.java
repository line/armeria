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
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.internal.common.RequestContextUtil.NOOP_CONTEXT_HOOK;
import static com.linecorp.armeria.internal.common.RequestContextUtil.mergeHooks;
import static com.linecorp.armeria.server.ServerSslContextUtil.buildSslContext;
import static com.linecorp.armeria.server.ServerSslContextUtil.validateSslContext;
import static com.linecorp.armeria.server.ServiceConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServiceConfig.validateRequestTimeoutMillis;
import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static com.linecorp.armeria.server.VirtualHost.validateHostnamePattern;
import static io.netty.handler.codec.http2.Http2Headers.PseudoHeaderName.isPseudoHeader;
import static java.util.Objects.requireNonNull;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.ByteStreams;
import com.google.common.net.HostAndPort;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.DependencyInjector;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestId;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SuccessFunction;
import com.linecorp.armeria.common.TlsSetters;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SelfSignedCertificate;
import com.linecorp.armeria.internal.server.RouteDecoratingService;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.internal.server.annotation.AnnotatedServiceExtensions;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.server.metric.MetricCollectingService;

import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AsciiString;
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
public final class VirtualHostBuilder implements TlsSetters, ServiceConfigsBuilder<VirtualHostBuilder> {

    private final ServerBuilder serverBuilder;
    private final boolean defaultVirtualHost;
    private final boolean portBased;
    private final List<ServiceConfigSetters<?>> serviceConfigSetters = new ArrayList<>();
    private final List<ShutdownSupport> shutdownSupports = new ArrayList<>();
    private final HttpHeadersBuilder defaultHeaders = HttpHeaders.builder();

    @Nullable
    private String defaultHostname;
    @Nullable
    private String hostnamePattern;
    private int port = -1;
    private String baseContextPath = "/";
    @Nullable
    private Supplier<SslContextBuilder> sslContextBuilderSupplier;
    @Nullable
    private Boolean tlsSelfSigned;
    @Nullable
    private SelfSignedCertificate selfSignedCertificate;
    private final List<Consumer<? super SslContextBuilder>> tlsCustomizers = new ArrayList<>();
    @Nullable
    private Boolean tlsAllowUnsafeCiphers;
    @Nullable
    private TlsEngineType tlsEngineType;
    private final LinkedList<RouteDecoratingService> routeDecoratingServices = new LinkedList<>();
    @Nullable
    private Function<? super VirtualHost, ? extends Logger> accessLoggerMapper;

    @Nullable
    private RejectedRouteHandler rejectedRouteHandler;
    @Nullable
    private ServiceNaming defaultServiceNaming;
    @Nullable
    private String defaultLogName;
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private AccessLogWriter accessLogWriter;
    @Nullable
    private AnnotatedServiceExtensions annotatedServiceExtensions;
    @Nullable
    private BlockingTaskExecutor blockingTaskExecutor;
    @Nullable
    private SuccessFunction successFunction;
    @Nullable
    private Long requestAutoAbortDelayMillis;
    @Nullable
    private Path multipartUploadsLocation;
    @Nullable
    private MultipartRemovalStrategy multipartRemovalStrategy;
    @Nullable
    private EventLoopGroup serviceWorkerGroup;
    @Nullable
    private Function<? super RoutingContext, ? extends RequestId> requestIdGenerator;
    @Nullable
    private ServiceErrorHandler errorHandler;
    private final VirtualHostContextPathServicesBuilder servicesBuilder =
            new VirtualHostContextPathServicesBuilder(this, this, ImmutableSet.of("/"));
    private Supplier<AutoCloseable> contextHook = NOOP_CONTEXT_HOOK;

    /**
     * Creates a new {@link VirtualHostBuilder}.
     *
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}
     * @param defaultVirtualHost tells whether this builder is the default virtual host builder or not
     */
    VirtualHostBuilder(ServerBuilder serverBuilder, boolean defaultVirtualHost) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
        this.defaultVirtualHost = defaultVirtualHost;
        portBased = false;
    }

    /**
     * Creates a new {@link VirtualHostBuilder}.
     *
     * @param serverBuilder the parent {@link ServerBuilder} to be returned by {@link #and()}
     * @param port the port that this virtual host binds to
     */
    VirtualHostBuilder(ServerBuilder serverBuilder, int port) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
        this.port = port;
        portBased = true;
        defaultVirtualHost = true;
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
     * Sets the base context path for this {@link VirtualHost}.
     * Services and decorators added to this {@link VirtualHost} will
     * be prefixed by the specified {@code baseContextPath}.
     */
    public VirtualHostBuilder baseContextPath(String baseContextPath) {
        this.baseContextPath = RouteUtil.ensureAbsolutePath(baseContextPath, "baseContextPath");
        return this;
    }

    /**
     * Sets the hostname pattern of this {@link VirtualHost}.
     * If the hostname pattern contains a port number such {@code *.example.com:8080}, the returned virtual host
     * will be bound to the {@code 8080} port. Otherwise, the virtual host will allow all active ports.
     *
     * @throws UnsupportedOperationException if this is the default {@link VirtualHostBuilder}
     *
     * @deprecated prefer specifying the hostnamePattern using {@link ServerBuilder#virtualHost(String)}
     *             or {@link ServerBuilder#withVirtualHost(String, Consumer)}
     */
    @Deprecated
    public VirtualHostBuilder hostnamePattern(String hostnamePattern) {
        if (defaultVirtualHost) {
            throw new UnsupportedOperationException(
                    "Cannot set hostnamePattern for the default virtual host builder");
        }

        checkArgument(!hostnamePattern.isEmpty(), "hostnamePattern is empty.");

        final HostAndPort hostAndPort = HostAndPort.fromString(hostnamePattern);
        if (hostAndPort.hasPort()) {
            port = hostAndPort.getPort();
            checkArgument(port >= 1 && port <= 65535, "port: %s (expected: 1-65535)", port);
            hostnamePattern = hostAndPort.getHost();
        }

        validateHostnamePattern(hostnamePattern);

        this.hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        return this;
    }

    VirtualHostBuilder hostnamePattern(String hostnamePattern, int port) {
        if (defaultVirtualHost) {
            throw new UnsupportedOperationException(
                    "Cannot set hostnamePattern for the default virtual host builder");
        }

        this.hostnamePattern = hostnamePattern;
        if (port >= 1 && port <= 65535) {
            this.port = port;
        }
        return this;
    }

    @Override
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile) {
        return (VirtualHostBuilder) TlsSetters.super.tls(keyCertChainFile, keyFile);
    }

    @Override
    public VirtualHostBuilder tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) {
        requireNonNull(keyCertChainFile, "keyCertChainFile");
        requireNonNull(keyFile, "keyFile");
        return tls(() -> SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword));
    }

    @Override
    public VirtualHostBuilder tls(InputStream keyCertChainInputStream, InputStream keyInputStream) {
        return (VirtualHostBuilder) TlsSetters.super.tls(keyCertChainInputStream, keyInputStream);
    }

    @Override
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

    @Override
    public VirtualHostBuilder tls(PrivateKey key, X509Certificate... keyCertChain) {
        return (VirtualHostBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    @Override
    public VirtualHostBuilder tls(PrivateKey key, Iterable<? extends X509Certificate> keyCertChain) {
        return (VirtualHostBuilder) TlsSetters.super.tls(key, keyCertChain);
    }

    @Override
    public VirtualHostBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                  X509Certificate... keyCertChain) {
        return (VirtualHostBuilder) TlsSetters.super.tls(key, keyPassword, keyCertChain);
    }

    @Override
    public VirtualHostBuilder tls(PrivateKey key, @Nullable String keyPassword,
                                  Iterable<? extends X509Certificate> keyCertChain) {
        requireNonNull(key, "key");
        requireNonNull(keyCertChain, "keyCertChain");
        for (X509Certificate keyCert : keyCertChain) {
            requireNonNull(keyCert, "keyCertChain contains null.");
        }

        return tls(() -> SslContextBuilder.forServer(key, keyPassword, keyCertChain));
    }

    @Override
    public VirtualHostBuilder tls(KeyManagerFactory keyManagerFactory) {
        requireNonNull(keyManagerFactory, "keyManagerFactory");
        return tls(() -> SslContextBuilder.forServer(keyManagerFactory));
    }

    private VirtualHostBuilder tls(Supplier<SslContextBuilder> sslContextBuilderSupplier) {
        requireNonNull(sslContextBuilderSupplier, "sslContextBuilderSupplier");
        checkState(this.sslContextBuilderSupplier == null, "TLS has been configured already.");
        checkState(!portBased,
                   "Cannot configure TLS to a port-based virtual host. Please configure to %s.tls()",
                   ServerBuilder.class.getSimpleName());
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
        return tlsSelfSigned(true);
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public VirtualHostBuilder tlsSelfSigned(boolean tlsSelfSigned) {
        checkState(!portBased, "Cannot configure self-signed to a port-based virtual host." +
                               " Please configure to %s.tlsSelfSigned()", ServerBuilder.class.getSimpleName());
        this.tlsSelfSigned = tlsSelfSigned;
        return this;
    }

    @Override
    public VirtualHostBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        requireNonNull(tlsCustomizer, "tlsCustomizer");
        checkState(!portBased,
                   "Cannot configure TLS to a port-based virtual host. Please configure to %s.tlsCustomizer()",
                   ServerBuilder.class.getSimpleName());
        tlsCustomizers.add(tlsCustomizer);
        return this;
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public VirtualHostBuilder tlsAllowUnsafeCiphers() {
        return tlsAllowUnsafeCiphers(true);
    }

    /**
     * Allows the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     *
     * <p>Note that enabling this option increases the security risk of your connection.
     * Use it only when you must communicate with a legacy system that does not support
     * secure cipher suites.
     * See <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-9.2.2">Section 9.2.2, RFC7540</a> for
     * more information. This option is disabled by default.
     *
     * @param tlsAllowUnsafeCiphers Whether to allow the unsafe ciphers
     *
     * @deprecated It's not recommended to enable this option. Use it only when you have no other way to
     *             communicate with an insecure peer than this.
     */
    @Deprecated
    public VirtualHostBuilder tlsAllowUnsafeCiphers(boolean tlsAllowUnsafeCiphers) {
        this.tlsAllowUnsafeCiphers = tlsAllowUnsafeCiphers;
        return this;
    }

    /**
     * The {@link TlsEngineType} that will be used for processing TLS connections.
     */
    @UnstableApi
    public VirtualHostBuilder tlsEngineType(TlsEngineType tlsEngineType) {
        requireNonNull(tlsEngineType, "tlsEngineType");
        this.tlsEngineType = tlsEngineType;
        return this;
    }

    /**
     * Returns a {@link VirtualHostContextPathServicesBuilder} which binds {@link HttpService}s under the
     * specified context paths.
     *
     * @see VirtualHostContextPathServicesBuilder
     */
    @UnstableApi
    public VirtualHostContextPathServicesBuilder contextPath(String... contextPaths) {
        return contextPath(ImmutableSet.copyOf(requireNonNull(contextPaths, "contextPaths")));
    }

    /**
     * Returns a {@link VirtualHostContextPathServicesBuilder} which binds {@link HttpService}s under the
     * specified context paths.
     *
     * @see VirtualHostContextPathServicesBuilder
     */
    @UnstableApi
    public VirtualHostContextPathServicesBuilder contextPath(Iterable<String> contextPaths) {
        requireNonNull(contextPaths, "contextPaths");
        return new VirtualHostContextPathServicesBuilder(this, this, ImmutableSet.copyOf(contextPaths));
    }

    /**
     * Configures an {@link HttpService} of the {@link VirtualHost} with the {@code customizer}.
     */
    public VirtualHostBuilder withRoute(Consumer<? super VirtualHostServiceBindingBuilder> customizer) {
        requireNonNull(customizer, "customizer");
        final VirtualHostServiceBindingBuilder builder = new VirtualHostServiceBindingBuilder(this);
        customizer.accept(builder);
        return this;
    }

    /**
     * Returns a {@link ServiceBindingBuilder} which is for binding an {@link HttpService} fluently.
     */
    @Override
    public VirtualHostServiceBindingBuilder route() {
        return new VirtualHostServiceBindingBuilder(this);
    }

    /**
     * Returns a {@link VirtualHostDecoratingServiceBindingBuilder} which is for binding
     * a {@code decorator} fluently. The specified decorator(s) is/are executed in reverse order of
     * the insertion.
     */
    @Override
    public VirtualHostDecoratingServiceBindingBuilder routeDecorator() {
        return new VirtualHostDecoratingServiceBindingBuilder(this);
    }

    /**
     * Binds the specified {@link HttpService} under the specified directory.
     * If the specified {@link HttpService} is an {@link HttpServiceWithRoutes}, the {@code pathPrefix} is added
     * to each {@link Route} of {@link HttpServiceWithRoutes#routes()}. For example, the
     * {@code serviceWithRoutes} in the following code will be bound to
     * ({@code "/foo/bar"}) and ({@code "/foo/baz"}):
     * <pre>{@code
     * > HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }
     * >
     * >     @Override
     * >     public Set<Route> routes() {
     * >         return Set.of(Route.builder().path("/bar").build(),
     * >                       Route.builder().path("/baz").build());
     * >     }
     * > };
     * >
     * > Server.builder()
     * >       .serviceUnder("/foo", serviceWithRoutes)
     * >       .build();
     * }</pre>
     */
    @Override
    public VirtualHostBuilder serviceUnder(String pathPrefix, HttpService service) {
        servicesBuilder.serviceUnder(pathPrefix, service);
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
    @Override
    public VirtualHostBuilder service(String pathPattern, HttpService service) {
        servicesBuilder.service(pathPattern, service);
        return this;
    }

    /**
     * Binds the specified {@link HttpService} at the specified {@link Route}.
     */
    @Override
    public VirtualHostBuilder service(Route route, HttpService service) {
        servicesBuilder.service(route, service);
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    public VirtualHostBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Iterable<? extends Function<? super HttpService, ? extends HttpService>> decorators) {
        servicesBuilder.service(serviceWithRoutes, decorators);
        return this;
    }

    /**
     * Decorates and binds the specified {@link HttpServiceWithRoutes} at multiple {@link Route}s.
     *
     * @param serviceWithRoutes the {@link HttpServiceWithRoutes}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @Override
    @SafeVarargs
    public final VirtualHostBuilder service(
            HttpServiceWithRoutes serviceWithRoutes,
            Function<? super HttpService, ? extends HttpService>... decorators) {
        servicesBuilder.service(serviceWithRoutes, decorators);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    @Override
    public VirtualHostBuilder annotatedService(Object service) {
        servicesBuilder.annotatedService(service);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(Object service,
                                               Object... exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(
            Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    @Override
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service) {
        servicesBuilder.annotatedService(pathPrefix, service);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Object... exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(pathPrefix, service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    @Override
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Iterable<?> exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(pathPrefix, service, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Object... exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(pathPrefix, service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters the {@link ExceptionHandlerFunction}s,
     *                                       the {@link RequestConverterFunction}s and/or
     *                                       the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(String pathPrefix, Object service,
                                               Function<? super HttpService, ? extends HttpService> decorator,
                                               Iterable<?> exceptionHandlersAndConverters) {
        servicesBuilder.annotatedService(pathPrefix, service, decorator, exceptionHandlersAndConverters);
        return this;
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlerFunctions the {@link ExceptionHandlerFunction}s
     * @param requestConverterFunctions the {@link RequestConverterFunction}s
     * @param responseConverterFunctions the {@link ResponseConverterFunction}s
     */
    @Override
    public VirtualHostBuilder annotatedService(
            String pathPrefix, Object service, Function<? super HttpService, ? extends HttpService> decorator,
            Iterable<? extends ExceptionHandlerFunction> exceptionHandlerFunctions,
            Iterable<? extends RequestConverterFunction> requestConverterFunctions,
            Iterable<? extends ResponseConverterFunction> responseConverterFunctions) {
        servicesBuilder.annotatedService(pathPrefix, service, decorator, exceptionHandlerFunctions,
                                         requestConverterFunctions, responseConverterFunctions);
        return this;
    }

    /**
     * Returns a new instance of {@link VirtualHostAnnotatedServiceBindingBuilder} to build
     * an annotated service fluently.
     */
    @Override
    public VirtualHostAnnotatedServiceBindingBuilder annotatedService() {
        return new VirtualHostAnnotatedServiceBindingBuilder(this);
    }

    VirtualHostBuilder addServiceConfigSetters(ServiceConfigSetters<?> serviceConfigSetters) {
        this.serviceConfigSetters.add(serviceConfigSetters);
        return this;
    }

    private List<ServiceConfigSetters<?>> getServiceConfigSetters(
            @Nullable VirtualHostBuilder defaultVirtualHostBuilder) {
        final List<ServiceConfigSetters<?>> serviceConfigSetters;
        if (defaultVirtualHostBuilder != null) {
            serviceConfigSetters = ImmutableList.<ServiceConfigSetters<?>>builder()
                                                .addAll(this.serviceConfigSetters)
                                                .addAll(defaultVirtualHostBuilder.serviceConfigSetters)
                                                .build();
        } else {
            serviceConfigSetters = ImmutableList.copyOf(this.serviceConfigSetters);
        }
        return serviceConfigSetters;
    }

    VirtualHostBuilder addRouteDecoratingService(RouteDecoratingService routeDecoratingService) {
        if (Flags.useLegacyRouteDecoratorOrdering()) {
            // The first inserted decorator is applied first.
            routeDecoratingServices.addLast(routeDecoratingService);
        } else {
            // The last inserted decorator is applied first.
            routeDecoratingServices.addFirst(routeDecoratingService);
        }
        return this;
    }

    @Nullable
    private Function<? super HttpService, ? extends HttpService> getRouteDecoratingService(
            @Nullable VirtualHostBuilder defaultVirtualHostBuilder, String baseContextPath) {
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
            final List<RouteDecoratingService> prefixed =
                    routeDecoratingServices.stream()
                                           .map(service -> service.withRoutePrefix(baseContextPath))
                                           .collect(toImmutableList());
            return RouteDecoratingService.newDecorator(Routers.ofRouteDecoratingService(prefixed),
                                                       routeDecoratingServices);
        } else {
            return null;
        }
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@code decorator}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decorator the {@link Function} that decorates {@link HttpService}s
     */
    @Override
    public VirtualHostBuilder decorator(Function<? super HttpService, ? extends HttpService> decorator) {
        servicesBuilder.decorator(decorator);
        return this;
    }

    /**
     * Decorates all {@link HttpService}s with the specified {@link DecoratingHttpServiceFunction}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public VirtualHostBuilder decorator(
            DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        servicesBuilder.decorator(decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public VirtualHostBuilder decorator(
            String pathPattern, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        servicesBuilder.decorator(pathPattern, decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@code pathPattern}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public VirtualHostBuilder decorator(
            String pathPattern, Function<? super HttpService, ? extends HttpService> decorator) {
        servicesBuilder.decorator(pathPattern, decorator);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@link Route}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param route the route being decorated
     * @param decorator the {@link Function} that decorates {@link HttpService}
     */
    @Override
    public VirtualHostBuilder decorator(
            Route route, Function<? super HttpService, ? extends HttpService> decorator) {
        servicesBuilder.decorator(route, decorator);
        return this;
    }

    /**
     * Decorates {@link HttpService}s whose {@link Route} matches the specified {@link Route}.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param route the route being decorated
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public VirtualHostBuilder decorator(
            Route route, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        servicesBuilder.decorator(route, decoratingHttpServiceFunction);
        return this;
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     */
    @Override
    public VirtualHostBuilder decoratorUnder(
            String prefix, Function<? super HttpService, ? extends HttpService> decorator) {
        servicesBuilder.decoratorUnder(prefix, decorator);
        return this;
    }

    /**
     * Decorates {@link HttpService}s under the specified directory.
     * The specified decorator(s) is/are executed in reverse order of the insertion.
     *
     * @param decoratingHttpServiceFunction the {@link DecoratingHttpServiceFunction} that decorates
     *                                      {@link HttpService}s
     */
    @Override
    public VirtualHostBuilder decoratorUnder(
            String prefix, DecoratingHttpServiceFunction decoratingHttpServiceFunction) {
        servicesBuilder.decoratorUnder(prefix, decoratingHttpServiceFunction);
        return this;
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
     * Adds the {@link ServiceErrorHandler} that handles exceptions thrown in this virtual host.
     * If multiple handlers are added, the latter is composed with the former using
     * {@link ServiceErrorHandler#orElse(ServiceErrorHandler)}.
     */
    public VirtualHostBuilder errorHandler(ServiceErrorHandler errorHandler) {
        requireNonNull(errorHandler, "errorHandler");
        if (this.errorHandler == null) {
            this.errorHandler = errorHandler;
        } else {
            this.errorHandler = this.errorHandler.orElse(errorHandler);
        }
        return this;
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
     * Adds the default HTTP header for an {@link HttpResponse} served by this {@link VirtualHost}.
     *
     * <p>Note that the default header could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#addHeader(CharSequence, Object)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#addHeader(CharSequence, Object)}</li>
     * </ul>
     */
    @UnstableApi
    public VirtualHostBuilder addHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.addObject(name, value);
        return this;
    }

    /**
     * Adds the default HTTP headers for an {@link HttpResponse} served by this {@link VirtualHost}.
     *
     * <p>Note that the default headers could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#addHeaders(Iterable)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#addHeaders(Iterable)}</li>
     * </ul>
     */
    @UnstableApi
    public VirtualHostBuilder addHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "headers");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.addObject(defaultHeaders);
        return this;
    }

    /**
     * Sets the default HTTP header for an {@link HttpResponse} served by this {@link VirtualHost}.
     *
     * <p>Note that the default header could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#setHeader(CharSequence, Object)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#setHeader(CharSequence, Object)}</li>
     * </ul>
     */
    @UnstableApi
    public VirtualHostBuilder setHeader(CharSequence name, Object value) {
        requireNonNull(name, "name");
        requireNonNull(value, "value");
        ensureNoPseudoHeader(name);
        defaultHeaders.setObject(name, value);
        return this;
    }

    /**
     * Sets the default HTTP headers for an {@link HttpResponse} served by this {@link VirtualHost}.
     *
     * <p>Note that the default headers could be overridden if the same {@link HttpHeaderNames} are defined in
     * one of the followings:
     * <ul>
     *   <li>{@link ServiceRequestContext#additionalResponseHeaders()}</li>
     *   <li>The {@link ResponseHeaders} of the {@link HttpResponse}</li>
     *   <li>{@link VirtualHostServiceBindingBuilder#setHeaders(Iterable)} or
     *       {@link VirtualHostAnnotatedServiceBindingBuilder#setHeaders(Iterable)}</li>
     * </ul>
     */
    @UnstableApi
    public VirtualHostBuilder setHeaders(
            Iterable<? extends Entry<? extends CharSequence, ?>> defaultHeaders) {
        requireNonNull(defaultHeaders, "headers");
        ensureNoPseudoHeader(defaultHeaders);
        this.defaultHeaders.setObject(defaultHeaders);
        return this;
    }

    static void ensureNoPseudoHeader(CharSequence name) {
        checkArgument(!isPseudoHeader(name), "Can't set a pseudo-header: %s", name);
    }

    static void ensureNoPseudoHeader(Iterable<? extends Entry<? extends CharSequence, ?>> headers) {
        for (Entry<? extends CharSequence, ?> header : headers) {
            ensureNoPseudoHeader(header.getKey());
        }
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
     * Sets the default naming rule for the name of services. If not set, the value set via
     * {@link ServerBuilder#defaultServiceNaming(ServiceNaming)} is used.
     */
    public VirtualHostBuilder defaultServiceNaming(ServiceNaming defaultServiceNaming) {
        this.defaultServiceNaming = requireNonNull(defaultServiceNaming);
        return this;
    }

    /**
     * Sets the default value of the {@link RequestLog#name()} property which is used when no name was set via
     * {@link RequestLogBuilder#name(String, String)}.
     *
     * @param defaultLogName the default log name.
     */
    public VirtualHostBuilder defaultLogName(String defaultLogName) {
        this.defaultLogName = requireNonNull(defaultLogName, "defaultLogName");
        return this;
    }

    @VisibleForTesting
    @Nullable
    String defaultLogName() {
        return defaultLogName;
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
        requireNonNull(accessLogWriter, "accessLogWriter");
        if (this.accessLogWriter != null) {
            this.accessLogWriter = this.accessLogWriter.andThen(accessLogWriter);
        } else {
            this.accessLogWriter = accessLogWriter;
        }
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(accessLogWriter));
        }
        return this;
    }

    /**
     * Sets the {@link ScheduledExecutorService} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link ScheduledExecutorService} when the
     *                       {@link Server} stops
     */
    public VirtualHostBuilder blockingTaskExecutor(ScheduledExecutorService blockingTaskExecutor,
                                                   boolean shutdownOnStop) {
        requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        return blockingTaskExecutor(BlockingTaskExecutor.of(blockingTaskExecutor), shutdownOnStop);
    }

    /**
     * Sets the {@link BlockingTaskExecutor} dedicated to the execution of blocking tasks or invocations.
     * If not set, {@linkplain CommonPools#blockingTaskExecutor() the common pool} is used.
     *
     * @param shutdownOnStop whether to shut down the {@link BlockingTaskExecutor} when the
     *                       {@link Server} stops
     */
    public VirtualHostBuilder blockingTaskExecutor(BlockingTaskExecutor blockingTaskExecutor,
                                                   boolean shutdownOnStop) {
        this.blockingTaskExecutor = requireNonNull(blockingTaskExecutor, "blockingTaskExecutor");
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(blockingTaskExecutor));
        }
        return this;
    }

    /**
     * Uses a newly created {@link BlockingTaskExecutor} with the specified number of threads dedicated to
     * the execution of blocking tasks or invocations.
     * The worker {@link EventLoopGroup} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of threads in the executor
     */
    public VirtualHostBuilder blockingTaskExecutor(int numThreads) {
        checkArgument(numThreads >= 0, "numThreads: %s (expected: >= 0)", numThreads);
        final BlockingTaskExecutor executor = BlockingTaskExecutor.builder()
                                                                  .numThreads(numThreads)
                                                                  .build();
        return blockingTaskExecutor(executor, true);
    }

    /**
     * Sets the {@link SuccessFunction} to define successful responses.
     * {@link MetricCollectingService} and {@link LoggingService} use this function.
     */
    @UnstableApi
    public VirtualHostBuilder successFunction(SuccessFunction successFunction) {
        this.successFunction = requireNonNull(successFunction, "successFunction");
        return this;
    }

    @VisibleForTesting
    @Nullable
    SuccessFunction successFunction() {
        return successFunction;
    }

    /**
     * Sets the amount of time to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@link Duration#ZERO} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public VirtualHostBuilder requestAutoAbortDelay(Duration delay) {
        return requestAutoAbortDelayMillis(requireNonNull(delay, "delay").toMillis());
    }

    /**
     * Sets the amount of time in millis to wait before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * This may be useful when you want to receive additional data even after closing the response.
     * Specify {@code 0} to abort the {@link HttpRequest} immediately. Any negative value will not
     * abort the request automatically. There is no delay by default.
     */
    @UnstableApi
    public VirtualHostBuilder requestAutoAbortDelayMillis(long delayMillis) {
        requestAutoAbortDelayMillis = delayMillis;
        return this;
    }

    /**
     * Sets the {@link Path} for storing the files uploaded from
     * {@code multipart/form-data} requests.
     *
     * @param multipartUploadsLocation the path of the directory which stores the files.
     */
    public VirtualHostBuilder multipartUploadsLocation(Path multipartUploadsLocation) {
        this.multipartUploadsLocation = requireNonNull(multipartUploadsLocation, "multipartUploadsLocation");
        return this;
    }

    @Nullable
    @VisibleForTesting
    Path multipartUploadsLocation() {
        return multipartUploadsLocation;
    }

    /**
     * Sets the {@link MultipartRemovalStrategy} that determines when to remove temporary files created
     * for multipart requests.
     * If not set, {@link MultipartRemovalStrategy#ON_RESPONSE_COMPLETION} is used by default.
     */
    @UnstableApi
    public VirtualHostBuilder multipartRemovalStrategy(MultipartRemovalStrategy removalStrategy) {
        multipartRemovalStrategy = requireNonNull(removalStrategy, "removalStrategy");
        return this;
    }

    /**
     * Sets the {@link Function} which generates a {@link RequestId}.
     * If not set, the value set via {@link ServerBuilder#requestIdGenerator(Function)} is used.
     *
     * @param requestIdGenerator the {@link Function} which generates a {@link RequestId}
     * @see RequestContext#id()
     */
    public VirtualHostBuilder requestIdGenerator(
            Function<? super RoutingContext, ? extends RequestId> requestIdGenerator) {
        this.requestIdGenerator = requireNonNull(requestIdGenerator, "requestIdGenerator");
        return this;
    }

    /**
     * Sets the {@link EventLoopGroup} dedicated to the execution of services' methods.
     * If not set, the work group of the belonging channel is used.
     *
     * @param shutdownOnStop whether to shut down the {@link EventLoopGroup} when the
     *                       {@link Server} stops
     */
    @UnstableApi
    public VirtualHostBuilder serviceWorkerGroup(EventLoopGroup serviceWorkerGroup,
                                                 boolean shutdownOnStop) {
        this.serviceWorkerGroup = requireNonNull(serviceWorkerGroup, "serviceWorkerGroup");
        if (shutdownOnStop) {
            shutdownSupports.add(ShutdownSupport.of(serviceWorkerGroup));
        }
        return this;
    }

    /**
     * Uses a newly created {@link EventLoopGroup} with the specified number of threads dedicated to
     * the execution of services' methods.
     * The worker {@link EventLoopGroup} will be shut down when the {@link Server} stops.
     *
     * @param numThreads the number of threads in the executor
     */
    @UnstableApi
    public VirtualHostBuilder serviceWorkerGroup(int numThreads) {
        final EventLoopGroup workerGroup = EventLoopGroups.newEventLoopGroup(numThreads);
        return serviceWorkerGroup(workerGroup, true);
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
     * Sets the {@link AutoCloseable} which will be called whenever this {@link RequestContext} is popped
     * from the {@link RequestContextStorage}.
     *
     * @param contextHook the {@link Supplier} that provides the {@link AutoCloseable}
     */
    @UnstableApi
    public VirtualHostBuilder contextHook(Supplier<? extends AutoCloseable> contextHook) {
        requireNonNull(contextHook, "contextHook");
        this.contextHook = mergeHooks(this.contextHook, contextHook);
        return this;
    }

    /**
     * Returns a newly-created {@link VirtualHost} based on the properties of this builder and the services
     * added to this builder.
     */
    VirtualHost build(VirtualHostBuilder template, DependencyInjector dependencyInjector,
                      @Nullable UnloggedExceptionsReporter unloggedExceptionsReporter,
                      ServerErrorHandler serverErrorHandler) {
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
        final ServiceNaming defaultServiceNaming =
                this.defaultServiceNaming != null ?
                this.defaultServiceNaming : template.defaultServiceNaming;
        final String defaultLogName =
                this.defaultLogName != null ?
                this.defaultLogName : template.defaultLogName;
        final long requestTimeoutMillis =
                this.requestTimeoutMillis != null ?
                this.requestTimeoutMillis : template.requestTimeoutMillis;
        final long maxRequestLength =
                this.maxRequestLength != null ?
                this.maxRequestLength : template.maxRequestLength;
        final boolean verboseResponses =
                this.verboseResponses != null ?
                this.verboseResponses : template.verboseResponses;
        final long requestAutoAbortDelayMillis =
                this.requestAutoAbortDelayMillis != null ?
                this.requestAutoAbortDelayMillis : template.requestAutoAbortDelayMillis;
        final RejectedRouteHandler rejectedRouteHandler =
                this.rejectedRouteHandler != null ?
                this.rejectedRouteHandler : template.rejectedRouteHandler;

        final AccessLogWriter accessLogWriter;
        if (this.accessLogWriter != null) {
            accessLogWriter = this.accessLogWriter;
        } else {
            accessLogWriter = template.accessLogWriter != null ?
                              template.accessLogWriter : AccessLogWriter.disabled();
        }

        final Function<? super VirtualHost, ? extends Logger> accessLoggerMapper =
                this.accessLoggerMapper != null ?
                this.accessLoggerMapper : template.accessLoggerMapper;

        final AnnotatedServiceExtensions extensions =
                annotatedServiceExtensions != null ?
                annotatedServiceExtensions : template.annotatedServiceExtensions;

        final BlockingTaskExecutor blockingTaskExecutor;
        if (this.blockingTaskExecutor != null) {
            blockingTaskExecutor = this.blockingTaskExecutor;
        } else {
            blockingTaskExecutor = template.blockingTaskExecutor;
        }

        final SuccessFunction successFunction;
        if (this.successFunction != null) {
            successFunction = this.successFunction;
        } else {
            successFunction = template.successFunction;
        }
        final Path multipartUploadsLocation =
                this.multipartUploadsLocation != null ?
                this.multipartUploadsLocation : template.multipartUploadsLocation;
        final MultipartRemovalStrategy multipartRemovalStrategy =
                this.multipartRemovalStrategy != null ?
                this.multipartRemovalStrategy : template.multipartRemovalStrategy;

        final HttpHeaders defaultHeaders =
                mergeDefaultHeaders(template.defaultHeaders, this.defaultHeaders.build());

        final Function<? super RoutingContext, ? extends RequestId> requestIdGenerator =
                this.requestIdGenerator != null ?
                this.requestIdGenerator : template.requestIdGenerator;
        final ServiceErrorHandler serviceErrorHandler = serverErrorHandler.asServiceErrorHandler();
        final ServiceErrorHandler defaultErrorHandler =
                errorHandler != null ? errorHandler.orElse(serviceErrorHandler) : serviceErrorHandler;

        final Supplier<AutoCloseable> contextHook = mergeHooks(template.contextHook, this.contextHook);

        final EventLoopGroup serviceWorkerGroup;
        if (this.serviceWorkerGroup != null) {
            serviceWorkerGroup = this.serviceWorkerGroup;
        } else if (template.serviceWorkerGroup != null) {
            serviceWorkerGroup = template.serviceWorkerGroup;
        } else {
            serviceWorkerGroup = serverBuilder.workerGroup;
        }

        assert defaultServiceNaming != null;
        assert rejectedRouteHandler != null;
        assert accessLoggerMapper != null;
        assert extensions != null;
        assert blockingTaskExecutor != null;
        assert successFunction != null;
        assert multipartUploadsLocation != null;
        assert multipartRemovalStrategy != null;
        assert requestIdGenerator != null;

        final List<ServiceConfig> serviceConfigs = getServiceConfigSetters(template)
                .stream()
                .flatMap(cfgSetters -> {
                    if (cfgSetters instanceof AbstractAnnotatedServiceConfigSetters) {
                        return ((AbstractAnnotatedServiceConfigSetters<?>) cfgSetters)
                                .buildServiceConfigBuilder(extensions, dependencyInjector).stream();
                    } else if (cfgSetters instanceof ServiceConfigBuilder) {
                        return Stream.of((ServiceConfigBuilder) cfgSetters);
                    } else {
                        // Should not reach here.
                        throw new Error("Unexpected service config setters type: " +
                                        cfgSetters.getClass().getSimpleName());
                    }
                }).map(cfgBuilder -> {
                    return cfgBuilder.build(defaultServiceNaming, requestTimeoutMillis, maxRequestLength,
                                            verboseResponses, accessLogWriter, blockingTaskExecutor,
                                            successFunction, requestAutoAbortDelayMillis,
                                            multipartUploadsLocation, multipartRemovalStrategy,
                                            serviceWorkerGroup, defaultHeaders,
                                            requestIdGenerator, defaultErrorHandler,
                                            unloggedExceptionsReporter, baseContextPath, contextHook);
                }).collect(toImmutableList());

        final ServiceConfig fallbackServiceConfig =
                new ServiceConfigBuilder(RouteBuilder.FALLBACK_ROUTE, "/", FallbackService.INSTANCE)
                        .build(defaultServiceNaming, requestTimeoutMillis, maxRequestLength, verboseResponses,
                               accessLogWriter, blockingTaskExecutor, successFunction,
                               requestAutoAbortDelayMillis, multipartUploadsLocation, multipartRemovalStrategy,
                               serviceWorkerGroup, defaultHeaders, requestIdGenerator,
                               defaultErrorHandler, unloggedExceptionsReporter, "/", contextHook);

        final ImmutableList.Builder<ShutdownSupport> builder = ImmutableList.builder();
        builder.addAll(shutdownSupports);
        builder.addAll(template.shutdownSupports);

        final TlsEngineType tlsEngineType = this.tlsEngineType != null ?
                                            this.tlsEngineType : template.tlsEngineType;
        assert tlsEngineType != null;
        final VirtualHost virtualHost =
                new VirtualHost(defaultHostname, hostnamePattern, port, sslContext(template, tlsEngineType),
                                tlsEngineType, serviceConfigs, fallbackServiceConfig, rejectedRouteHandler,
                                accessLoggerMapper, defaultServiceNaming, defaultLogName, requestTimeoutMillis,
                                maxRequestLength, verboseResponses, accessLogWriter, blockingTaskExecutor,
                                requestAutoAbortDelayMillis, successFunction, multipartUploadsLocation,
                                multipartRemovalStrategy, serviceWorkerGroup, builder.build(),
                                requestIdGenerator);

        final Function<? super HttpService, ? extends HttpService> decorator =
                getRouteDecoratingService(template, baseContextPath);
        return decorator != null ? virtualHost.decorate(decorator) : virtualHost;
    }

    static HttpHeaders mergeDefaultHeaders(HttpHeadersBuilder lowPriorityHeaders,
                                           HttpHeaders highPriorityHeaders) {
        if (lowPriorityHeaders.isEmpty()) {
            return highPriorityHeaders;
        }

        if (highPriorityHeaders.isEmpty()) {
            return lowPriorityHeaders.build();
        }

        final HttpHeadersBuilder headersBuilder = highPriorityHeaders.toBuilder();
        for (final AsciiString name : lowPriorityHeaders.names()) {
            if (!headersBuilder.contains(name)) {
                headersBuilder.add(name, lowPriorityHeaders.getAll(name));
            }
        }
        return headersBuilder.build();
    }

    @Nullable
    private SslContext sslContext(VirtualHostBuilder template, TlsEngineType tlsEngineType) {
        if (portBased) {
            return null;
        }
        SslContext sslContext = null;
        boolean releaseSslContextOnFailure = false;
        try {
            final boolean tlsAllowUnsafeCiphers =
                    this.tlsAllowUnsafeCiphers != null ?
                    this.tlsAllowUnsafeCiphers : template.tlsAllowUnsafeCiphers;

            // Whether the `SslContext` came (or was created) from this `VirtualHost`'s properties.
            boolean sslContextFromThis = false;

            // Build a new SslContext or use a user-specified one for backward compatibility.
            if (sslContextBuilderSupplier != null) {
                sslContext = buildSslContext(sslContextBuilderSupplier, tlsEngineType, tlsAllowUnsafeCiphers,
                                             tlsCustomizers);
                sslContextFromThis = true;
                releaseSslContextOnFailure = true;
            } else if (template.sslContextBuilderSupplier != null) {
                sslContext = buildSslContext(template.sslContextBuilderSupplier, tlsEngineType,
                                             tlsAllowUnsafeCiphers, template.tlsCustomizers);
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
                    final SelfSignedCertificate ssc;
                    try {
                        ssc = selfSignedCertificate();
                    } catch (Exception e) {
                        throw new RuntimeException("failed to create a self signed certificate", e);
                    }

                    sslContext = buildSslContext(() -> SslContextBuilder.forServer(ssc.certificate(),
                                                                                   ssc.privateKey()),
                                                 tlsEngineType,
                                                 tlsAllowUnsafeCiphers,
                                                 tlsCustomizers);
                    releaseSslContextOnFailure = true;
                }
            }

            // Reject if a user called `tlsCustomizer()` without `tls()` or `tlsSelfSigned()`.
            checkState(sslContextFromThis || tlsCustomizers.isEmpty(),
                       "Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");

            // Validate the built `SslContext`.
            if (sslContext != null) {
                validateSslContext(sslContext, tlsEngineType);
                checkState(sslContext.isServer(), "sslContextBuilder built a client SSL context.");
            }
            releaseSslContextOnFailure = false;
        } finally {
            if (releaseSslContextOnFailure) {
                ReferenceCountUtil.release(sslContext);
            }
        }
        return sslContext;
    }

    private SelfSignedCertificate selfSignedCertificate() throws CertificateException {
        if (selfSignedCertificate == null) {
            return selfSignedCertificate = new SelfSignedCertificate(defaultHostname);
        }
        return selfSignedCertificate;
    }

    boolean equalsHostnamePattern(String validHostnamePattern, int port) {
        checkArgument(!validHostnamePattern.isEmpty(), "hostnamePattern is empty.");

        if (this.port != port) {
            return false;
        }
        return validHostnamePattern.equals(hostnamePattern);
    }

    int port() {
        return port;
    }

    boolean defaultVirtualHost() {
        return defaultVirtualHost;
    }
}
