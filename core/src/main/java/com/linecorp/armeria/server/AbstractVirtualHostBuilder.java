/*
 * Copyright 2016 LINE Corporation
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
import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static java.util.Objects.requireNonNull;

import java.io.File;
import java.nio.charset.Charset;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.MediaTypeSet;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import com.linecorp.armeria.internal.crypto.BouncyCastleKeyFactoryProvider;
import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Contains information for the build of the virtual host.
 *
 * @see ChainedVirtualHostBuilder
 * @see VirtualHostBuilder
 */
@SuppressWarnings("rawtypes")
abstract class AbstractVirtualHostBuilder<B extends AbstractVirtualHostBuilder> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractVirtualHostBuilder.class);

    private static final ApplicationProtocolConfig HTTPS_ALPN_CFG = new ApplicationProtocolConfig(
            Protocol.ALPN,
            // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
            SelectorFailureBehavior.NO_ADVERTISE,
            // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
            SelectedListenerFailureBehavior.ACCEPT,
            ApplicationProtocolNames.HTTP_2,
            ApplicationProtocolNames.HTTP_1_1);

    private final String defaultHostname;
    private final String hostnamePattern;
    private final List<ServiceConfig> services = new ArrayList<>();
    @Nullable
    private SslContext sslContext;
    @Nullable
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;
    @Nullable
    private Function<VirtualHost, Logger> accessLoggerMapper;

    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;
    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;

    /**
     * Creates a new {@link VirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     */
    AbstractVirtualHostBuilder() {
        this(SystemInfo.hostname(), "*");
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     */
    AbstractVirtualHostBuilder(String hostnamePattern) {
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);

        if ("*".equals(hostnamePattern)) {
            defaultHostname = SystemInfo.hostname();
        } else if (hostnamePattern.startsWith("*.")) {
            defaultHostname = hostnamePattern.substring(2);
        } else {
            defaultHostname = hostnamePattern;
        }

        this.hostnamePattern = hostnamePattern;
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with
     * the default host name and the specified hostname pattern.
     */
    AbstractVirtualHostBuilder(String defaultHostname, String hostnamePattern) {
        requireNonNull(defaultHostname, "defaultHostname");

        defaultHostname = normalizeDefaultHostname(defaultHostname);
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);
        ensureHostnamePatternMatchesDefaultHostname(hostnamePattern, defaultHostname);

        this.defaultHostname = defaultHostname;
        this.hostnamePattern = hostnamePattern;
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@link SslContext}.
     */
    public B tls(SslContext sslContext) {
        this.sslContext = VirtualHost.validateSslContext(requireNonNull(sslContext, "sslContext"));
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile}
     * and cleartext {@code keyFile}.
     */
    public B tls(File keyCertChainFile, File keyFile) throws SSLException {
        tls(keyCertChainFile, keyFile, null, sslContextBuilder -> { /* noop */ });
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * cleartext {@code keyFile} and {@code tlsCustomizer}.
     */
    public B tls(File keyCertChainFile, File keyFile, Consumer<SslContextBuilder> tlsCustomizer)
            throws SSLException {
        tls(keyCertChainFile, keyFile, null, tlsCustomizer);
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     */
    public B tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) throws SSLException {
        tls(keyCertChainFile, keyFile, keyPassword, sslContextBuilder -> { /* noop */ });
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile}, {@code keyPassword} and {@code tlsCustomizer}.
     */
    public B tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword,
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
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyManagerFactory}
     * and {@code tlsCustomizer}.
     */
    public B tls(KeyManagerFactory keyManagerFactory,
                 Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        requireNonNull(keyManagerFactory, "keyManagerFactory");
        requireNonNull(tlsCustomizer, "tlsCustomizer");

        tls(buildSslContext(() -> SslContextBuilder.forServer(keyManagerFactory), tlsCustomizer));
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with an auto-generated self-signed certificate.
     * <strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @throws CertificateException if failed to generate a self-signed certificate
     */
    public B tlsSelfSigned() throws SSLException, CertificateException {
        final SelfSignedCertificate ssc = new SelfSignedCertificate(defaultHostname);
        return tls(ssc.certificate(), ssc.privateKey());
    }

    private static SslContext buildSslContext(
            Supplier<SslContextBuilder> builderSupplier,
            Consumer<SslContextBuilder> tlsCustomizer) throws SSLException {
        try {
            return BouncyCastleKeyFactoryProvider.call(() -> {
                final SslContextBuilder builder = builderSupplier.get();

                builder.sslProvider(Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK);
                builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
                builder.applicationProtocolConfig(HTTPS_ALPN_CFG);

                tlsCustomizer.accept(builder);

                return builder.build();
            });
        } catch (RuntimeException | SSLException e) {
            throw e;
        } catch (Exception e) {
            throw new SSLException("failed to configure TLS: " + e, e);
        }
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost}.
     *
     * @deprecated Use {@link #tls(SslContext)}.
     */
    @Deprecated
    public B sslContext(SslContext sslContext) {
        return tls(sslContext);
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile} and cleartext {@code keyFile}.
     *
     * @deprecated Use {@link #tls(File, File)}.
     */
    @Deprecated
    public B sslContext(
            SessionProtocol protocol, File keyCertChainFile, File keyFile) throws SSLException {
        sslContext(protocol, keyCertChainFile, keyFile, null);
        return self();
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile}, {@code keyFile} and {@code keyPassword}.
     *
     * @deprecated Use {@link #tls(File, File, String)}.
     */
    @Deprecated
    public B sslContext(
            SessionProtocol protocol,
            File keyCertChainFile, File keyFile, @Nullable String keyPassword) throws SSLException {

        if (requireNonNull(protocol, "protocol") != SessionProtocol.HTTPS) {
            throw new IllegalArgumentException("unsupported protocol: " + protocol);
        }

        return tls(keyCertChainFile, keyFile, keyPassword);
    }

    /**
     * Binds the specified {@link Service} at the specified path pattern.
     *
     * @deprecated Use {@link #service(String, Service)} instead.
     */
    @Deprecated
    public B serviceAt(String pathPattern, Service<HttpRequest, HttpResponse> service) {
        return service(pathPattern, service);
    }

    /**
     * Binds the specified {@link Service} under the specified directory.
     */
    public B serviceUnder(String pathPrefix, Service<HttpRequest, HttpResponse> service) {
        service(PathMapping.ofPrefix(pathPrefix), service);
        return self();
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
    public B service(String pathPattern, Service<HttpRequest, HttpResponse> service) {
        service(PathMapping.of(pathPattern), service);
        return self();
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     */
    public B service(PathMapping pathMapping, Service<HttpRequest, HttpResponse> service) {
        services.add(new ServiceConfig(pathMapping, service, null));
        return self();
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     *
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    public B service(PathMapping pathMapping, Service<HttpRequest, HttpResponse> service, String loggerName) {
        services.add(new ServiceConfig(pathMapping, service, loggerName));
        return self();
    }

    /**
     * Decorates and binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithPathMappings the {@link ServiceWithPathMappings}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    public B service(ServiceWithPathMappings<HttpRequest, HttpResponse> serviceWithPathMappings,
                     Iterable<Function<? super Service<HttpRequest, HttpResponse>,
                             ? extends Service<HttpRequest, HttpResponse>>> decorators) {
        requireNonNull(serviceWithPathMappings, "serviceWithPathMappings");
        requireNonNull(serviceWithPathMappings.pathMappings(), "serviceWithPathMappings.pathMappings()");
        requireNonNull(decorators, "decorators");

        Service<HttpRequest, HttpResponse> decorated = serviceWithPathMappings;
        for (Function<? super Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> d : decorators) {
            checkNotNull(d, "decorators contains null: %s", decorators);
            decorated = d.apply(decorated);
            checkNotNull(decorated, "A decorator returned null: %s", d);
        }

        final Service<HttpRequest, HttpResponse> finalDecorated = decorated;
        serviceWithPathMappings.pathMappings().forEach(pathMapping -> service(pathMapping, finalDecorated));
        return self();
    }

    /**
     * Decorates and binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s
     * of the default {@link VirtualHost}.
     *
     * @param serviceWithPathMappings the {@link ServiceWithPathMappings}.
     * @param decorators the decorator functions, which will be applied in the order specified.
     */
    @SafeVarargs
    public final B service(ServiceWithPathMappings<HttpRequest, HttpResponse> serviceWithPathMappings,
                           Function<? super Service<HttpRequest, HttpResponse>,
                                   ? extends Service<HttpRequest, HttpResponse>>... decorators) {
        return service(serviceWithPathMappings, ImmutableList.copyOf(requireNonNull(decorators, "decorators")));
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public B annotatedService(Object service) {
        return annotatedService("/", service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     *
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public B annotatedService(Object service, Object... exceptionHandlersAndConverters) {
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
    public B annotatedService(Object service,
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
    public B annotatedService(String pathPrefix, Object service) {
        return annotatedService(pathPrefix, service, Function.identity(), ImmutableList.of());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     *
     * @param exceptionHandlersAndConverters instances of {@link ExceptionHandlerFunction},
     *                                       {@link RequestConverterFunction} and/or
     *                                       {@link ResponseConverterFunction}
     */
    public B annotatedService(String pathPrefix, Object service,
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
    public B annotatedService(String pathPrefix, Object service,
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
    public B annotatedService(String pathPrefix, Object service,
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
    public B annotatedService(String pathPrefix, Object service,
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
            service(e.pathMapping(), s);
        });
        return self();
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    B decorator(Function<T, R> decorator) {

        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
                (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return self();
    }

    @SuppressWarnings("unchecked")
    final B self() {
        return (B) this;
    }

    /**
     * Returns a newly-created {@link VirtualHost} based on the properties of this builder and the services
     * added to this builder.
     */
    protected VirtualHost build() {
        final List<MediaType> producibleTypes = new ArrayList<>();

        // Collect producible media types over this virtual host.
        services.forEach(s -> producibleTypes.addAll(s.pathMapping().produceTypes()));

        final VirtualHost virtualHost =
                new VirtualHost(defaultHostname, hostnamePattern, sslContext, services,
                                new MediaTypeSet(producibleTypes), RejectedPathMappingHandler.DISABLED,
                                accessLoggerMapper, requestContentPreviewerFactory,
                                responseContentPreviewerFactory);
        return decorator != null ? virtualHost.decorate(decorator) : virtualHost;
    }

    /**
     * Sets the access logger mapper of this {@link VirtualHost}.
     * When {@link #build()} is called, this {@link VirtualHost} gets {@link Logger}
     * via the {@code mapper} for writing access logs.
     */
    public B accessLogger(Function<VirtualHost, Logger> mapper) {
        accessLoggerMapper = requireNonNull(mapper, "mapper");
        return self();
    }

    /**
     * Sets the {@link Logger} of this {@link VirtualHost}, which is used for writing access logs.
     */
    public B accessLogger(Logger logger) {
        requireNonNull(logger, "logger");
        return accessLogger(host -> logger);
    }

    /**
     * Sets the {@link Logger} named {@code loggerName} of this {@link VirtualHost},
     * which is used for writing access logs.
     */
    public B accessLogger(String loggerName) {
        requireNonNull(loggerName, "loggerName");
        return accessLogger(host -> LoggerFactory.getLogger(loggerName));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request of this {@link VirtualHost}.
     */
    public B requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a response of this {@link VirtualHost}.
     */
    public B responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for a request and a response of this {@link VirtualHost}.
     */
    public B contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return self();
    }

    /**
     * Sets the {@link ContentPreviewerFactory} creating a {@link ContentPreviewer} which produces the preview
     * with the maxmium {@code length} limit for a request and a response of this {@link VirtualHost}.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following cases.
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     * @param defaultCharset the default charset for a request/response with unspecified charset in
     *                       {@code "content-type"} header.
     */
    public B contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} creating a {@link ContentPreviewer} which produces the preview
     * with the maxmium {@code length} limit for a request and a response of this {@link VirtualHost}.
     * The previewer is enabled only if the content type of a request/response meets
     * any of the following cases.
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    public B contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    @Override
    public String toString() {
        return VirtualHost.toString(getClass(), defaultHostname, hostnamePattern, sslContext, services);
    }
}
