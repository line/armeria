/*
 * Copyright 2020 LINE Corporation
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
package com.linecorp.armeria.dropwizard;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.TrustManagerFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.math.LongMath;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.AccessLog;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Compression;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Http1;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Http2;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Port;
import com.linecorp.armeria.dropwizard.ArmeriaSettings.Proxy;
import com.linecorp.armeria.internal.common.util.ResourceUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.server.logging.AccessLogWriter;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.util.NetUtil;

/**
 * A utility class which is used to configure a {@link ServerBuilder} with the {@code ArmeriaSettings}.
 */
final class ArmeriaConfigurationUtil {
    private static final Logger logger = LoggerFactory.getLogger(ArmeriaConfigurationUtil.class);

    private static final String[] EMPTY_PROTOCOL_NAMES = new String[0];

    private static final Port DEFAULT_PORT = new Port().setPort(8080)
                                                       .setProtocol(SessionProtocol.HTTP);

    private static final int DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING = 1024;

    /**
     * The pattern for data size text.
     * TODO(ikhoon): a-z seems rather broad, assuming just (kMGTP)?(Bb)
     */
    private static final Pattern DATA_SIZE_PATTERN = Pattern.compile("^([+]?\\d+)([a-zA-Z]{0,2})$");

    /**
     * Configures the {@link ServerBuilder} with the specified {@code settings}.
     */
    static void configureServer(ServerBuilder serverBuilder, ArmeriaSettings settings) {
        requireNonNull(serverBuilder, "serverBuilder");
        requireNonNull(settings, "settings");

        if (settings.getGracefulShutdownQuietPeriodMillis() >= 0 &&
            settings.getGracefulShutdownTimeoutMillis() >= 0) {
            serverBuilder.gracefulShutdownTimeoutMillis(settings.getGracefulShutdownQuietPeriodMillis(),
                                                        settings.getGracefulShutdownTimeoutMillis());
            logger.debug("Set graceful shutdown timeout: quiet period {} ms, timeout {} ms",
                         settings.getGracefulShutdownQuietPeriodMillis(),
                         settings.getGracefulShutdownTimeoutMillis());
        }
        if (settings.getMaxRequestLength() != null) {
            serverBuilder.maxRequestLength(settings.getMaxRequestLength());
        }
        if (settings.getMaxNumConnections() != null) {
            serverBuilder.maxNumConnections(settings.getMaxNumConnections());
        }
        if (!settings.isDateHeaderEnabled()) {
            serverBuilder.disableDateHeader();
        }
        if (!settings.isServerHeaderEnabled()) {
            serverBuilder.disableServerHeader();
        }
        if (settings.getDefaultHostname() != null) {
            serverBuilder.defaultHostname(settings.getDefaultHostname());
        }
        if (settings.isVerboseResponses()) {
            serverBuilder.verboseResponses(true);
        }

        if (settings.getPorts().isEmpty()) {
            serverBuilder.port(new ServerPort(DEFAULT_PORT.getPort(), DEFAULT_PORT.getProtocols()));
        } else {
            configurePorts(serverBuilder, settings.getPorts());
        }

        if (settings.getSsl() != null) {
            configureTls(serverBuilder, settings.getSsl());
        }
        if (settings.getCompression() != null) {
            configureCompression(serverBuilder, settings.getCompression());
        }
        if (settings.getHttp1() != null) {
            configureHttp1(serverBuilder, settings.getHttp1());
        }
        if (settings.getHttp2() != null) {
            configureHttp2(serverBuilder, settings.getHttp2());
        }
        if (settings.getProxy() != null) {
            configureProxy(serverBuilder, settings.getProxy());
        }
        if (settings.getAccessLog() != null) {
            configureAccessLog(serverBuilder, settings.getAccessLog());
        }
    }

    /**
     * Adds {@link Port}s to the specified {@link ServerBuilder}.
     */
    private static void configurePorts(ServerBuilder server, List<Port> ports) {
        requireNonNull(server, "server");
        requireNonNull(ports, "ports");
        ports.forEach(p -> {
            final String ip = p.getIp();
            final String iface = p.getIface();
            final int port = p.getPort();
            final List<SessionProtocol> protocols = firstNonNull(p.getProtocols(),
                                                                 ImmutableList.of(SessionProtocol.HTTP));

            if (ip == null) {
                if (iface == null) {
                    server.port(new ServerPort(port, protocols));
                } else {
                    try {
                        final Enumeration<InetAddress> e = NetworkInterface.getByName(iface).getInetAddresses();
                        while (e.hasMoreElements()) {
                            server.port(new ServerPort(new InetSocketAddress(e.nextElement(), port),
                                                       protocols));
                        }
                    } catch (SocketException e) {
                        throw new IllegalStateException("Failed to find an iface: " + iface, e);
                    }
                }
            } else if (iface == null) {
                if (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip)) {
                    final byte[] bytes = NetUtil.createByteArrayFromIpAddressString(ip);
                    try {
                        server.port(new ServerPort(new InetSocketAddress(
                                InetAddress.getByAddress(bytes), port), protocols));
                    } catch (UnknownHostException e) {
                        // Should never happen.
                        throw new Error(e);
                    }
                } else {
                    throw new IllegalStateException("invalid IP address: " + ip);
                }
            } else {
                throw new IllegalStateException("A port cannot have both IP and iface: " + p);
            }
        });
    }

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    private static void configureTls(ServerBuilder sb, ArmeriaSettings.Ssl ssl) {
        configureTls(sb, ssl, null, null);
    }

    /**
     * Adds SSL/TLS context to the specified {@link ServerBuilder}.
     */
    private static void configureTls(ServerBuilder sb, ArmeriaSettings.Ssl ssl,
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
            ArmeriaSettings.Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
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
            ArmeriaSettings.Ssl ssl, @Nullable Supplier<KeyStore> sslStoreProvider) throws Exception {
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
        final URL url = ResourceUtil.getURL(resource);
        store.load(url.openStream(), password != null ? password.toCharArray()
                                                      : null);
        return store;
    }

    /**
     * Configures a decorator for encoding the content of the HTTP responses sent from the server.
     */
    private static Function<? super HttpService, EncodingService> contentEncodingDecorator(
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
    private static long parseDataSize(String dataSizeText) {
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
                    // TODO(ikhoon): Simplify with Math.pow?
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

    private static void configureCompression(ServerBuilder serverBuilder, Compression compression) {
        if (compression.isEnabled()) {
            final int minBytesToForceChunkedAndEncoding;
            final String minResponseSize = compression.getMinResponseSize();
            if (minResponseSize == null) {
                minBytesToForceChunkedAndEncoding = DEFAULT_MIN_BYTES_TO_FORCE_CHUNKED_AND_ENCODING;
            } else {
                minBytesToForceChunkedAndEncoding = Ints.saturatedCast(parseDataSize(minResponseSize));
            }
            serverBuilder.decorator(contentEncodingDecorator(compression.getMimeTypes(),
                                                             compression.getExcludedUserAgents(),
                                                             minBytesToForceChunkedAndEncoding));
        }
    }

    private static void configureHttp1(ServerBuilder serverBuilder, Http1 http1) {
        if (http1.getMaxInitialLineLength() != null) {
            serverBuilder.http1MaxInitialLineLength(http1.getMaxInitialLineLength());
        }
        if (http1.getMaxChunkSize() != null) {
            serverBuilder.http1MaxChunkSize((int) parseDataSize(http1.getMaxChunkSize()));
        }
    }

    private static void configureHttp2(ServerBuilder serverBuilder, Http2 http2) {
        if (http2.getInitialConnectionWindowSize() != null) {
            serverBuilder.http2InitialConnectionWindowSize(
                    (int) parseDataSize(http2.getInitialConnectionWindowSize()));
        }
        if (http2.getInitialStreamWindowSize() != null) {
            serverBuilder.http2InitialStreamWindowSize((int) parseDataSize(http2.getInitialStreamWindowSize()));
        }
        if (http2.getMaxFrameSize() != null) {
            serverBuilder.http2MaxFrameSize((int) parseDataSize(http2.getMaxFrameSize()));
        }
        if (http2.getMaxHeaderListSize() != null) {
            serverBuilder.http2MaxHeaderListSize((int) parseDataSize(http2.getMaxHeaderListSize()));
        }
    }

    private static void configureProxy(ServerBuilder serverBuilder, Proxy proxy) {
        if (proxy.getMaxTlvSize() != null) {
            serverBuilder.proxyProtocolMaxTlvSize((int) parseDataSize(proxy.getMaxTlvSize()));
        }
    }

    private static void configureAccessLog(ServerBuilder serverBuilder, AccessLog accessLog) {
        if ("common".equals(accessLog.getType())) {
            serverBuilder.accessLogWriter(AccessLogWriter.common(), true);
        } else if ("combined".equals(accessLog.getType())) {
            serverBuilder.accessLogWriter(AccessLogWriter.combined(), true);
        } else if ("custom".equals(accessLog.getType())) {
            serverBuilder
                    .accessLogWriter(AccessLogWriter.custom(accessLog.getFormat()), true);
        }
    }

    private ArmeriaConfigurationUtil() {}
}
