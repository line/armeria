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

import static com.linecorp.armeria.server.VirtualHost.ensureHostnamePatternMatchesDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeDefaultHostname;
import static com.linecorp.armeria.server.VirtualHost.normalizeHostnamePattern;
import static java.util.Objects.requireNonNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import javax.annotation.Nullable;
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

    private static final String LOCAL_HOSTNAME;

    static {
        // Try the '/usr/bin/hostname' command first, which is more reliable.
        Process process = null;
        String hostname = null;
        try {
            process = Runtime.getRuntime().exec("hostname");
            final String line = new BufferedReader(new InputStreamReader(process.getInputStream())).readLine();
            if (line == null) {
                logger.debug("The 'hostname' command returned nothing; " +
                             "using InetAddress.getLocalHost() instead");
            } else {
                hostname = normalizeDefaultHostname(line.trim());
                logger.info("Hostname: {} (from 'hostname' command)", hostname);
            }
        } catch (Exception e) {
            logger.debug("Failed to get the hostname using the 'hostname' command; " +
                         "using InetAddress.getLocalHost() instead", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        if (hostname == null) {
            try {
                hostname = normalizeDefaultHostname(InetAddress.getLocalHost().getHostName());
                logger.info("Hostname: {} (from InetAddress.getLocalHost())", hostname);
            } catch (Exception e) {
                hostname = "localhost";
                logger.warn("Failed to get the hostname using InetAddress.getLocalHost(); " +
                            "using 'localhost' instead", e);
            }
        }

        LOCAL_HOSTNAME = hostname;
    }

    private final String defaultHostname;
    private final String hostnamePattern;
    private final List<ServiceConfig> services = new ArrayList<>();
    @Nullable
    private SslContext sslContext;
    @Nullable
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;
    @Nullable
    private Function<VirtualHost, Logger> accessLoggerMapper;

    /**
     * Creates a new {@link VirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     */
    AbstractVirtualHostBuilder() {
        this(LOCAL_HOSTNAME, "*");
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     */
    AbstractVirtualHostBuilder(String hostnamePattern) {
        hostnamePattern = normalizeHostnamePattern(hostnamePattern);

        if ("*".equals(hostnamePattern)) {
            defaultHostname = LOCAL_HOSTNAME;
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
        tls(keyCertChainFile, keyFile, null);
        return self();
    }

    /**
     * Configures SSL or TLS of this {@link VirtualHost} with the specified {@code keyCertChainFile},
     * {@code keyFile} and {@code keyPassword}.
     */
    public B tls(File keyCertChainFile, File keyFile, @Nullable String keyPassword) throws SSLException {
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

        final SslContext sslCtx;

        try {
            sslCtx = BouncyCastleKeyFactoryProvider.call(() -> {
                final SslContextBuilder builder =
                        SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword);

                builder.sslProvider(Flags.useOpenSsl() ? SslProvider.OPENSSL : SslProvider.JDK);
                builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
                builder.applicationProtocolConfig(HTTPS_ALPN_CFG);

                return builder.build();
            });
        } catch (RuntimeException | SSLException e) {
            throw e;
        } catch (Exception e) {
            throw new SSLException("failed to configure TLS: " + e, e);
        }

        tls(sslCtx);
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
     * Binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s.
     */
    public <T extends ServiceWithPathMappings<HttpRequest, HttpResponse>>
    B service(T serviceWithPathMappings) {
        return service(serviceWithPathMappings, Function.identity());
    }

    /**
     * Decorates and binds the specified {@link ServiceWithPathMappings} at multiple {@link PathMapping}s.
     */
    public <T extends ServiceWithPathMappings<HttpRequest, HttpResponse>,
            R extends Service<HttpRequest, HttpResponse>>
    B service(T serviceWithPathMappings, Function<? super T, R> decorator) {
        requireNonNull(serviceWithPathMappings, "serviceWithPathMappings");
        requireNonNull(serviceWithPathMappings.pathMappings(), "serviceWithPathMappings.pathMappings()");
        requireNonNull(decorator, "decorator");
        final Service<HttpRequest, HttpResponse> decorated = decorator.apply(serviceWithPathMappings);
        serviceWithPathMappings.pathMappings().forEach(pathMapping -> service(pathMapping, decorated));
        return self();
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
                                new MediaTypeSet(producibleTypes), accessLoggerMapper);
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

    @Override
    public String toString() {
        return VirtualHost.toString(getClass(), defaultHostname, hostnamePattern, sslContext, services);
    }
}
