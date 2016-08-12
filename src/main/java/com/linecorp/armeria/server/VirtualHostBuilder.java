/*
 * Copyright 2015 LINE Corporation
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
import java.util.function.Function;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.NativeLibraries;

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
 * Builds a new {@link VirtualHost}.
 * <h2>Example</h2>
 * <pre>{@code
 * VirtualHostBuilder vhb = new VirtualHostBuilder("*.example.com");
 * vhb.serviceAt("/foo", new FooService())
 *    .serviceUnder("/bar/", new BarService())
 *    .service(PathMapping.ofRegex("^/baz/.*", new BazService());
 *
 * VirtualHost vh = vhb.build();
 * }</pre>
 *
 * @see PathMapping
 */
public final class VirtualHostBuilder {

    private static final Logger logger = LoggerFactory.getLogger(VirtualHostBuilder.class);

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
    private Function<Service<Request, Response>, Service<Request, Response>> decorator;

    /**
     * Creates a new {@link VirtualHostBuilder} whose hostname pattern is {@code "*"} (match-all).
     */
    public VirtualHostBuilder() {
        this(LOCAL_HOSTNAME, "*");
    }

    /**
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     */
    public VirtualHostBuilder(String hostnamePattern) {
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
     * Creates a new {@link VirtualHostBuilder} with the specified hostname pattern.
     */
    public VirtualHostBuilder(String defaultHostname, String hostnamePattern) {
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
    public VirtualHostBuilder sslContext(SslContext sslContext) {
        this.sslContext = VirtualHost.validateSslContext(requireNonNull(sslContext, "sslContext"));
        return this;
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile} and cleartext {@code keyFile}.
     */
    public VirtualHostBuilder sslContext(
            SessionProtocol protocol, File keyCertChainFile, File keyFile) throws SSLException {
        return sslContext(protocol, keyCertChainFile, keyFile, null);
    }

    /**
     * Sets the {@link SslContext} of this {@link VirtualHost} from the specified {@link SessionProtocol},
     * {@code keyCertChainFile}, {@code keyFile} and {@code keyPassword}.
     */
    public VirtualHostBuilder sslContext(
            SessionProtocol protocol,
            File keyCertChainFile, File keyFile, String keyPassword) throws SSLException {

        if (requireNonNull(protocol, "protocol") != SessionProtocol.HTTPS) {
            throw new IllegalArgumentException("unsupported protocol: " + protocol);
        }

        final SslContextBuilder builder = SslContextBuilder.forServer(keyCertChainFile, keyFile, keyPassword);

        builder.sslProvider(NativeLibraries.isOpenSslAvailable() ? SslProvider.OPENSSL : SslProvider.JDK);
        builder.ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE);
        builder.applicationProtocolConfig(HTTPS_ALPN_CFG);

        sslContext(builder.build());

        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified exact path.
     */
    public VirtualHostBuilder serviceAt(String exactPath, Service<?, ?> service) {
        return service(PathMapping.ofExact(exactPath), service);
    }

    /**
     * Binds the specified {@link Service} under the specified directory..
     */
    public VirtualHostBuilder serviceUnder(String pathPrefix, Service<?, ?> service) {
        return service(PathMapping.ofPrefix(pathPrefix), service);
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     */
    public VirtualHostBuilder service(PathMapping pathMapping, Service<?, ?> service) {
        services.add(new ServiceConfig(pathMapping, service, null));
        return this;
    }

    /**
     * Binds the specified {@link Service} at the specified {@link PathMapping}.
     *
     * @param loggerName the name of the {@linkplain ServiceRequestContext#logger() service logger};
     *                   must be a string of valid Java identifier names concatenated by period ({@code '.'}),
     *                   such as a package name or a fully-qualified class name
     */
    public VirtualHostBuilder service(PathMapping pathMapping, Service<?, ?> service, String loggerName) {
        services.add(new ServiceConfig(pathMapping, service, loggerName));
        return this;
    }

    /**
     * Decorates all {@link Service}s with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates a {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<T_I, T_O>, T_I extends Request, T_O extends Response,
            R extends Service<R_I, R_O>, R_I extends Request, R_O extends Response>
    VirtualHostBuilder decorator(Function<T, R> decorator) {

        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<Request, Response>, Service<Request, Response>> castDecorator =
                (Function<Service<Request, Response>, Service<Request, Response>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    /**
     * Creates a new {@link VirtualHost}.
     */
    public VirtualHost build() {
        final VirtualHost virtualHost = new VirtualHost(defaultHostname, hostnamePattern, sslContext, services);
        return decorator != null ? virtualHost.decorate(decorator) : virtualHost;
    }

    @Override
    public String toString() {
        return VirtualHost.toString(getClass(), defaultHostname, hostnamePattern, sslContext, services);
    }
}
