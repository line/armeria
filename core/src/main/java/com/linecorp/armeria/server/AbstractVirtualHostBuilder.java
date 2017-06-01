/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.http.HttpRequest;
import com.linecorp.armeria.common.http.HttpResponse;
import com.linecorp.armeria.common.http.HttpSessionProtocols;
import com.linecorp.armeria.common.util.NativeLibraries;
import com.linecorp.armeria.server.http.dynamic.ResponseConverter;

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
                logger.warn("The 'hostname' command returned nothing; " +
                            "using InetAddress.getLocalHost() instead", line);
            } else {
                hostname = normalizeDefaultHostname(line.trim());
                logger.info("Hostname: {} (via 'hostname' command)", hostname);
            }
        } catch (Exception e) {
            logger.warn("Failed to get the hostname using the 'hostname' command; " +
                        "using InetAddress.getLocalHost() instead", e);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }

        if (hostname == null) {
            try {
                hostname = normalizeDefaultHostname(InetAddress.getLocalHost().getHostName());
                logger.info("Hostname: {} (via InetAddress.getLocalHost())", hostname);
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
    private SslContext sslContext;
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;

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
     * Sets the {@link SslContext} of this {@link VirtualHost}.
     */
    public B sslContext(SslContext sslContext) {
        this.sslContext = VirtualHost.validateSslContext(requireNonNull(sslContext, "sslContext"));
        return self();
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile} and cleartext {@code keyFile}.
     */
    public B sslContext(
            SessionProtocol protocol, File keyCertChainFile, File keyFile) throws SSLException {
        sslContext(protocol, keyCertChainFile, keyFile, null);
        return self();
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile}, {@code keyFile} and {@code keyPassword}.
     */
    public B sslContext(
            SessionProtocol protocol,
            File keyCertChainFile, File keyFile, String keyPassword) throws SSLException {

        if (requireNonNull(protocol, "protocol") != HttpSessionProtocols.HTTPS) {
            throw new IllegalArgumentException("unsupported protocol: " + protocol);
        }

        final SslContextBuilder builder = SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword);

        builder.sslProvider(NativeLibraries.isOpenSslAvailable() ? SslProvider.OPENSSL : SslProvider.JDK);
        builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
        builder.applicationProtocolConfig(HTTPS_ALPN_CFG);

        sslContext(builder.build());
        return self();
    }

    /**
     * Binds the specified {@link Service} at the the specified path pattern. e.g.
     * <ul>
     *   <li>{@code /login} (no path parameters)</li>
     *   <li>{@code /users/{userId}} (curly-brace style)</li>
     *   <li>{@code /list/:productType/by/:ordering} (colon style)</li>
     * </ul>
     */
    public B serviceAt(String pathPattern, Service<? super HttpRequest, ? extends HttpResponse> service) {
        service(PathMapping.of(pathPattern), service);
        return self();
    }

    /**
     * Binds the specified {@link Service} under the specified directory.
     */
    public B serviceUnder(String pathPrefix, Service<? super HttpRequest, ? extends HttpResponse> service) {
        service(PathMapping.ofPrefix(pathPrefix), service);
        return self();
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     */
    public B service(PathMapping pathMapping, Service<? super HttpRequest, ? extends HttpResponse> service) {
        services.add(new ServiceConfig(pathMapping, service, null));
        return self();
    }

    /**
     * @deprecated Use a logging framework integration such as {@code RequestContextExportingAppender} in
     *             {@code armeria-logback}.
     */
    @Deprecated
    public B service(PathMapping pathMapping,
                     Service<? super HttpRequest, ? extends HttpResponse> service,
                     String loggerName) {
        services.add(new ServiceConfig(pathMapping, service, loggerName));
        return self();
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public B service(Object service) {
        return service("/", service);
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public B service(
            Object service,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<? super HttpRequest, ? extends HttpResponse>> decorator) {
        return service("/", service, decorator);
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public B service(Object service, Map<Class<?>, ResponseConverter> converters) {
        return service("/", service, converters);
    }

    /**
     * Binds the specified annotated service object under the path prefix {@code "/"}.
     */
    public B service(
            Object service, Map<Class<?>, ResponseConverter> converters,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<? super HttpRequest, ? extends HttpResponse>> decorator) {
        return service("/", service, converters, decorator);
    }


    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public B service(String pathPrefix, Object service) {
        return service(pathPrefix, service, ImmutableMap.of(), Function.identity());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public B service(
            String pathPrefix, Object service,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<? super HttpRequest, ? extends HttpResponse>> decorator) {
        return service(pathPrefix, service, ImmutableMap.of(), decorator);
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public B service(String pathPrefix, Object service, Map<Class<?>, ResponseConverter> converters) {
        return service(pathPrefix, service, converters, Function.identity());
    }

    /**
     * Binds the specified annotated service object under the specified path prefix.
     */
    public B service(
            String pathPrefix, Object service, Map<Class<?>, ResponseConverter> converters,
            Function<Service<HttpRequest, HttpResponse>,
                     ? extends Service<? super HttpRequest, ? extends HttpResponse>> decorator) {

        requireNonNull(pathPrefix, "pathPrefix");
        requireNonNull(service, "service");
        requireNonNull(converters, "converters");
        requireNonNull(decorator, "decorator");

        final List<AnnotatedHttpService> entries =
                AnnotatedHttpServices.build(pathPrefix, service, converters);
        entries.forEach(e -> service(e.pathMapping(), decorator.apply(e)));
        return self();
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    @SuppressWarnings("unchecked")
    public <T extends Service<T_I, T_O>, T_I extends HttpRequest, T_O extends HttpResponse,
            R extends Service<R_I, R_O>, R_I extends HttpRequest, R_O extends HttpResponse>
    B decorator(Function<T, R> decorator) {

        requireNonNull(decorator, "decorator");

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
     * Creates a new {@link VirtualHost}.
     */
    protected VirtualHost build() {
        final VirtualHost virtualHost = new VirtualHost(defaultHostname, hostnamePattern, sslContext, services);
        return decorator != null ? virtualHost.decorate(decorator) : virtualHost;
    }

    @Override
    public String toString() {
        return VirtualHost.toString(getClass(), defaultHostname, hostnamePattern, sslContext, services);
    }
}
