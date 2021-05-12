/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.common;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.DnsResolverGroupBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.InetAddressPredicates;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientService;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.FileServiceBuilder;
import com.linecorp.armeria.server.file.HttpFile;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;
import io.netty.util.ReferenceCountUtil;

/**
 * The system properties that affect Armeria's runtime behavior.
 */
public final class Flags {

    private static final Logger logger = LoggerFactory.getLogger(Flags.class);

    private static final Splitter CSV_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private static final String PREFIX = "com.linecorp.armeria.";

    private static final int NUM_CPU_CORES = Runtime.getRuntime().availableProcessors();

    private static final String DEFAULT_VERBOSE_EXCEPTION_SAMPLER_SPEC = "rate-limit=10";
    private static final String VERBOSE_EXCEPTION_SAMPLER_SPEC;
    private static final Sampler<Class<? extends Throwable>> VERBOSE_EXCEPTION_SAMPLER;

    @Nullable
    private static final Predicate<InetAddress> PREFERRED_IP_V4_ADDRESSES;

    static {
        final String spec = getNormalized("verboseExceptions", DEFAULT_VERBOSE_EXCEPTION_SAMPLER_SPEC, val -> {
            if ("true".equals(val) || "false".equals(val)) {
                return true;
            }

            try {
                Sampler.of(val);
                return true;
            } catch (Exception e) {
                // Invalid sampler specification
                return false;
            }
        });

        switch (spec) {
            case "true":
            case "always":
                VERBOSE_EXCEPTION_SAMPLER_SPEC = "always";
                VERBOSE_EXCEPTION_SAMPLER = Sampler.always();
                break;
            case "false":
            case "never":
                VERBOSE_EXCEPTION_SAMPLER_SPEC = "never";
                VERBOSE_EXCEPTION_SAMPLER = Sampler.never();
                break;
            default:
                VERBOSE_EXCEPTION_SAMPLER_SPEC = spec;
                VERBOSE_EXCEPTION_SAMPLER = new ExceptionSampler(VERBOSE_EXCEPTION_SAMPLER_SPEC);
        }

        final List<Predicate<InetAddress>> preferredIpV4Addresses =
                CSV_SPLITTER.splitToList(getNormalized("preferredIpV4Addresses", "", unused -> true))
                            .stream()
                            .map(cidr -> {
                                try {
                                    return InetAddressPredicates.ofCidr(cidr);
                                } catch (Exception e) {
                                    logger.warn("Failed to parse a preferred IPv4: {}", cidr);
                                }
                                return null;
                            })
                            .filter(Objects::nonNull)
                            .collect(toImmutableList());
        switch (preferredIpV4Addresses.size()) {
            case 0:
                PREFERRED_IP_V4_ADDRESSES = null;
                break;
            case 1:
                PREFERRED_IP_V4_ADDRESSES = preferredIpV4Addresses.get(0);
                break;
            default:
                PREFERRED_IP_V4_ADDRESSES = inetAddress -> {
                    for (Predicate<InetAddress> preferredIpV4Addr : preferredIpV4Addresses) {
                        if (preferredIpV4Addr.test(inetAddress)) {
                            return true;
                        }
                    }
                    return false;
                };
        }
    }

    private static final boolean VERBOSE_SOCKET_EXCEPTIONS = getBoolean("verboseSocketExceptions", false);

    private static final boolean VERBOSE_RESPONSES = getBoolean("verboseResponses", false);

    @Nullable
    private static final String REQUEST_CONTEXT_STORAGE_PROVIDER =
            System.getProperty(PREFIX + "requestContextStorageProvider");

    private static final boolean USE_EPOLL = getBoolean("useEpoll", TransportType.EPOLL.isAvailable(),
                                                        value -> TransportType.EPOLL.isAvailable() || !value);

    private static final String DEFAULT_TRANSPORT_TYPE = USE_EPOLL ? "epoll" : "nio";
    private static final String TRANSPORT_TYPE_NAME = getNormalized("transportType",
                                                                    DEFAULT_TRANSPORT_TYPE,
                                                                    val -> {
                                                                        switch (val) {
                                                                            case "nio":
                                                                            case "epoll":
                                                                            case "io_uring":
                                                                                return true;
                                                                            default:
                                                                                return false;
                                                                        }
                                                                    });
    private static final TransportType TRANSPORT_TYPE;

    @Nullable
    private static Boolean useOpenSsl;
    @Nullable
    private static Boolean dumpOpenSslInfo;

    private static final int DEFAULT_MAX_NUM_CONNECTIONS = Integer.MAX_VALUE;
    private static final int MAX_NUM_CONNECTIONS =
            getInt("maxNumConnections", DEFAULT_MAX_NUM_CONNECTIONS, value -> value > 0);

    private static final int DEFAULT_NUM_COMMON_WORKERS = NUM_CPU_CORES * 2;
    private static final int NUM_COMMON_WORKERS =
            getInt("numCommonWorkers", DEFAULT_NUM_COMMON_WORKERS, value -> value > 0);

    private static final int DEFAULT_NUM_COMMON_BLOCKING_TASK_THREADS = 200; // from Tomcat default maxThreads
    private static final int NUM_COMMON_BLOCKING_TASK_THREADS =
            getInt("numCommonBlockingTaskThreads",
                   DEFAULT_NUM_COMMON_BLOCKING_TASK_THREADS,
                   value -> value > 0);

    private static final long DEFAULT_DEFAULT_MAX_REQUEST_LENGTH = 10 * 1024 * 1024; // 10 MiB
    private static final long DEFAULT_MAX_REQUEST_LENGTH =
            getLong("defaultMaxRequestLength",
                    DEFAULT_DEFAULT_MAX_REQUEST_LENGTH,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_MAX_RESPONSE_LENGTH = 10 * 1024 * 1024; // 10 MiB
    private static final long DEFAULT_MAX_RESPONSE_LENGTH =
            getLong("defaultMaxResponseLength",
                    DEFAULT_DEFAULT_MAX_RESPONSE_LENGTH,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS = 10 * 1000; // 10 seconds
    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS =
            getLong("defaultRequestTimeoutMillis",
                    DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS,
                    value -> value >= 0);

    // Use slightly greater value than the default request timeout so that clients have a higher chance of
    // getting proper 503 Service Unavailable response when server-side timeout occurs.
    private static final long DEFAULT_DEFAULT_RESPONSE_TIMEOUT_MILLIS = 15 * 1000; // 15 seconds
    private static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS =
            getLong("defaultResponseTimeoutMillis",
                    DEFAULT_DEFAULT_RESPONSE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS = 3200; // 3.2 seconds
    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS =
            getLong("defaultConnectTimeoutMillis",
                    DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    value -> value > 0);

    private static final long DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS = 1000; // 1 second
    private static final long DEFAULT_WRITE_TIMEOUT_MILLIS =
            getLong("defaultWriteTimeoutMillis",
                    DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    // Use slightly greater value than the client-side default so that clients close the connection more often.
    private static final long DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS = 15000; // 15 seconds
    private static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS =
            getLong("defaultServerIdleTimeoutMillis",
                    DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS = 10000; // 10 seconds
    private static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS =
            getLong("defaultClientIdleTimeoutMillis",
                    DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_PING_INTERVAL_MILLIS = 0; // Disabled
    private static final long DEFAULT_PING_INTERVAL_MILLIS =
            getLong("defaultPingIntervalMillis",
                    DEFAULT_DEFAULT_PING_INTERVAL_MILLIS,
                    value -> value >= 0);

    private static final int DEFAULT_DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled
    private static final int DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION =
            getInt("defaultMaxServerNumRequestsPerConnection",
                   DEFAULT_DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION,
                   value -> value >= 0);

    private static final int DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION =
            getInt("defaultMaxClientNumRequestsPerConnection",
                   DEFAULT_DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION,
                    value -> value >= 0);

    private static final long DEFAULT_DEFAULT_MAX_CONNECTION_AGE_MILLIS = 0; // Disabled
    private static final long DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS =
            getLong("defaultMaxServerConnectionAgeMillis",
                    DEFAULT_DEFAULT_MAX_CONNECTION_AGE_MILLIS,
                    value -> value >= 0);

    private static final long DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS =
            getLong("defaultMaxClientConnectionAgeMillis",
                    DEFAULT_DEFAULT_MAX_CONNECTION_AGE_MILLIS,
                    value -> value >= 0);

    private static final int DEFAULT_DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE = 1024 * 1024; // 1MiB
    private static final int DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            getInt("defaultHttp2InitialConnectionWindowSize",
                   DEFAULT_DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE,
                   value -> value > 0);

    private static final int DEFAULT_DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE = 1024 * 1024; // 1MiB
    private static final int DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            getInt("defaultHttp2InitialStreamWindowSize",
                   DEFAULT_DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE,
                   value -> value > 0);

    private static final int DEFAULT_DEFAULT_HTTP2_MAX_FRAME_SIZE = 16384; // From HTTP/2 specification
    private static final int DEFAULT_HTTP2_MAX_FRAME_SIZE =
            getInt("defaultHttp2MaxFrameSize",
                   DEFAULT_DEFAULT_HTTP2_MAX_FRAME_SIZE,
                   value -> value >= Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND &&
                            value <= Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND);

    // Can't use 0xFFFFFFFFL because some implementations use a signed 32-bit integer to store HTTP/2 SETTINGS
    // parameter values, thus anything greater than 0x7FFFFFFF will break them or make them unhappy.
    private static final long DEFAULT_DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION = Integer.MAX_VALUE;
    private static final long DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION =
            getLong("defaultHttp2MaxStreamsPerConnection",
                    DEFAULT_DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION,
                    value -> value > 0 && value <= 0xFFFFFFFFL);

    // from Netty default maxHeaderSize
    private static final long DEFAULT_DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE = 8192;
    private static final long DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE =
            getLong("defaultHttp2MaxHeaderListSize",
                    DEFAULT_DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE,
                    value -> value > 0 && value <= 0xFFFFFFFFL);

    private static final int DEFAULT_DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH = 4096; // from Netty
    private static final int DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH =
            getInt("defaultHttp1MaxInitialLineLength",
                   DEFAULT_DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH,
                   value -> value >= 0);

    private static final int DEFAULT_DEFAULT_HTTP1_MAX_HEADER_SIZE = 8192; // from Netty
    private static final int DEFAULT_MAX_HTTP1_HEADER_SIZE =
            getInt("defaultHttp1MaxHeaderSize",
                   DEFAULT_DEFAULT_HTTP1_MAX_HEADER_SIZE,
                   value -> value >= 0);

    private static final int DEFAULT_DEFAULT_HTTP1_MAX_CHUNK_SIZE = 8192; // from Netty
    private static final int DEFAULT_HTTP1_MAX_CHUNK_SIZE =
            getInt("defaultHttp1MaxChunkSize",
                   DEFAULT_DEFAULT_HTTP1_MAX_CHUNK_SIZE,
                   value -> value >= 0);

    private static final boolean DEFAULT_USE_HTTP2_PREFACE = getBoolean("defaultUseHttp2Preface", true);
    private static final boolean DEFAULT_USE_HTTP1_PIPELINING = getBoolean("defaultUseHttp1Pipelining", false);

    private static final String DEFAULT_DEFAULT_BACKOFF_SPEC =
            "exponential=200:10000,jitter=0.2";
    private static final String DEFAULT_BACKOFF_SPEC =
            getNormalized("defaultBackoffSpec", DEFAULT_DEFAULT_BACKOFF_SPEC, value -> {
                try {
                    Backoff.of(value);
                    return true;
                } catch (Exception e) {
                    // Invalid backoff specification
                    return false;
                }
            });

    private static final int DEFAULT_DEFAULT_MAX_TOTAL_ATTEMPTS = 10;
    private static final int DEFAULT_MAX_TOTAL_ATTEMPTS =
            getInt("defaultMaxTotalAttempts",
                   DEFAULT_DEFAULT_MAX_TOTAL_ATTEMPTS,
                   value -> value > 0);

    private static final String DEFAULT_ROUTE_CACHE_SPEC = "maximumSize=4096";
    @Nullable
    private static final String ROUTE_CACHE_SPEC =
            nullableCaffeineSpec("routeCache", DEFAULT_ROUTE_CACHE_SPEC);

    private static final String DEFAULT_ROUTE_DECORATOR_CACHE_SPEC = "maximumSize=4096";
    @Nullable
    private static final String ROUTE_DECORATOR_CACHE_SPEC =
            nullableCaffeineSpec("routeDecoratorCache", DEFAULT_ROUTE_DECORATOR_CACHE_SPEC);

    private static final String DEFAULT_PARSED_PATH_CACHE_SPEC = "maximumSize=4096";
    @Nullable
    private static final String PARSED_PATH_CACHE_SPEC =
            nullableCaffeineSpec("parsedPathCache", DEFAULT_PARSED_PATH_CACHE_SPEC);

    private static final String DEFAULT_HEADER_VALUE_CACHE_SPEC = "maximumSize=4096";
    @Nullable
    private static final String HEADER_VALUE_CACHE_SPEC =
            nullableCaffeineSpec("headerValueCache", DEFAULT_HEADER_VALUE_CACHE_SPEC);

    private static final String DEFAULT_CACHED_HEADERS =
            ":authority,:scheme,:method,accept-encoding,content-type";
    private static final List<String> CACHED_HEADERS =
            CSV_SPLITTER.splitToList(getNormalized(
                    "cachedHeaders", DEFAULT_CACHED_HEADERS, CharMatcher.ascii()::matchesAllOf));

    private static final String DEFAULT_FILE_SERVICE_CACHE_SPEC = "maximumSize=1024";
    @Nullable
    private static final String FILE_SERVICE_CACHE_SPEC =
            nullableCaffeineSpec("fileServiceCache", DEFAULT_FILE_SERVICE_CACHE_SPEC);

    private static final String DEFAULT_DNS_CACHE_SPEC = "maximumSize=4096";
    private static final String DNS_CACHE_SPEC =
            nonnullCaffeineSpec("dnsCacheSpec", DEFAULT_DNS_CACHE_SPEC);

    private static final String DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY = "unhandled";
    private static final ExceptionVerbosity ANNOTATED_SERVICE_EXCEPTION_VERBOSITY =
            exceptionLoggingMode("annotatedServiceExceptionVerbosity",
                                 DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY);

    private static final boolean USE_JDK_DNS_RESOLVER = getBoolean("useJdkDnsResolver", false);

    private static final boolean REPORT_BLOCKED_EVENT_LOOP =
            getBoolean("reportBlockedEventLoop", true);

    private static final boolean VALIDATE_HEADERS = getBoolean("validateHeaders", true);

    private static final boolean
            DEFAULT_TLS_ALLOW_UNSAFE_CIPHERS = getBoolean("tlsAllowUnsafeCiphers", false);

    private static final Set<TransientServiceOption> TRANSIENT_SERVICE_OPTIONS =
            Sets.immutableEnumSet(
                    Streams.stream(CSV_SPLITTER.split(getNormalized(
                            "transientServiceOptions", "", val -> {
                                try {
                                    Streams.stream(CSV_SPLITTER.split(val))
                                           .forEach(feature -> TransientServiceOption
                                                   .valueOf(Ascii.toUpperCase(feature)));
                                    return true;
                                } catch (Exception e) {
                                    return false;
                                }
                            }))).map(feature -> TransientServiceOption.valueOf(Ascii.toUpperCase(feature)))
                           .collect(toImmutableSet()));

    private static final boolean
            DEFAULT_USE_LEGACY_ROUTE_DECORATOR_ORDERING = getBoolean("useLegacyRouteDecoratorOrdering", false);

    // Use a slightly larger value than client idle timeout to ensure connections aren't dropped
    private static final int DEFAULT_TCP_USER_TIMEOUT_MILLIS = 15_000;
    private static final int TCP_USER_TIMEOUT_MILLIS =
            getInt("tcpUserTimeoutMillis", DEFAULT_TCP_USER_TIMEOUT_MILLIS, value -> value >= 0);

    static {
        TransportType type = null;
        switch (TRANSPORT_TYPE_NAME) {
            case "io_uring":
                if (TransportType.IO_URING.isAvailable()) {
                    logger.info("Using io_uring");
                    type = TransportType.IO_URING;
                } else {
                    final Throwable cause = TransportType.IO_URING.unavailabilityCause();
                    if (cause != null) {
                        logger.info("io_uring not available: {}", cause.toString());
                    } else {
                        logger.info("io_uring not available: ?");
                    }
                }
                // fallthrough
            case "epoll":
                if (TransportType.EPOLL.isAvailable() && type == null) {
                    logger.info("Using /dev/epoll");
                    type = TransportType.EPOLL;
                } else {
                    final Throwable cause = TransportType.EPOLL.unavailabilityCause();
                    if (cause != null) {
                        logger.info("/dev/epoll not available: {}", cause.toString());
                    } else {
                        logger.info("/dev/epoll not available: ?");
                    }
                }
                // fallthrough
            default:
                if (type == null) {
                    logger.info("Using nio");
                    type = TransportType.NIO;
                }
                break;
        }
        TRANSPORT_TYPE = type;
    }

    /**
     * Returns the {@link Sampler} that determines whether to retain the stack trace of the exceptions
     * that are thrown frequently by Armeria.
     *
     * @see #verboseExceptionSamplerSpec()
     */
    public static Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return VERBOSE_EXCEPTION_SAMPLER;
    }

    /**
     * Returns the specification string of the {@link Sampler} that determines whether to retain the stack
     * trace of the exceptions that are thrown frequently by Armeria. A sampled exception will have the stack
     * trace while the others will have an empty stack trace to eliminate the cost of capturing the stack
     * trace.
     *
     * <p>The default value of this flag is {@value #DEFAULT_VERBOSE_EXCEPTION_SAMPLER_SPEC}, which retains
     * the stack trace of the exceptions at the maximum rate of 10 exceptions/sec.
     * Specify the {@code -Dcom.linecorp.armeria.verboseExceptions=<specification>} JVM option to override
     * the default. See {@link Sampler#of(String)} for the specification string format.</p>
     */
    public static String verboseExceptionSamplerSpec() {
        // XXX(trustin): Is it worth allowing to specify different specs for different exception types?
        return VERBOSE_EXCEPTION_SAMPLER_SPEC;
    }

    /**
     * Returns whether to log the socket exceptions which are mostly harmless. If enabled, the following
     * exceptions will be logged:
     * <ul>
     *   <li>{@link ClosedChannelException}</li>
     *   <li>{@link ClosedSessionException}</li>
     *   <li>{@link IOException} - 'Connection reset/closed/aborted by peer'</li>
     *   <li>'Broken pipe'</li>
     *   <li>{@link Http2Exception} - 'Stream closed'</li>
     *   <li>{@link SSLException} - 'SSLEngine closed already'</li>
     * </ul>
     *
     * <p>It is recommended to keep this flag disabled, because it increases the amount of log messages for
     * the errors you usually do not have control over, e.g. unexpected socket disconnection due to network
     * or remote peer issues.</p>
     *
     * <p>This flag is disabled by default.
     * Specify the {@code -Dcom.linecorp.armeria.verboseSocketExceptions=true} JVM option to enable it.</p>
     *
     * @see Exceptions#isExpected(Throwable)
     */
    public static boolean verboseSocketExceptions() {
        return VERBOSE_SOCKET_EXCEPTIONS;
    }

    /**
     * Returns whether the verbose response mode is enabled. When enabled, the server responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the server responses will not expose such server-side details to the client.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.verboseResponses=true}
     * JVM option or use {@link ServerBuilder#verboseResponses(boolean)} to enable it.
     */
    public static boolean verboseResponses() {
        return VERBOSE_RESPONSES;
    }

    /**
     * Returns the fully qualified class name of {@link RequestContextStorageProvider} that is used to choose
     * when multiple {@link RequestContextStorageProvider}s exist.
     *
     * <p>The default value of this flag is {@code null}, which means only one
     * {@link RequestContextStorageProvider} must be found via Java SPI. If there are more than one,
     * you must specify the {@code -Dcom.linecorp.armeria.requestContextStorageProvider=<FQCN>} JVM option to
     * choose the {@link RequestContextStorageProvider}.
     */
    @Nullable
    public static String requestContextStorageProvider() {
        return REQUEST_CONTEXT_STORAGE_PROVIDER;
    }

    /**
     * Returns whether the JNI-based {@code /dev/epoll} socket I/O is enabled. When enabled on Linux, Armeria
     * uses {@code /dev/epoll} directly for socket I/O. When disabled, {@code java.nio} socket API is used
     * instead.
     *
     * <p>This flag is enabled by default for supported platforms. Specify the
     * {@code -Dcom.linecorp.armeria.useEpoll=false} JVM option to disable it.
     *
     * @deprecated Use {@link #transportType()} and {@code -Dcom.linecorp.armeria.transportType=epoll}.
     */
    @Deprecated
    public static boolean useEpoll() {
        return USE_EPOLL;
    }

    /**
     * Returns the {@link TransportType} that will be used for socket I/O in Armeria.
     *
     * <p>The default value of this flag is {@code "epoll"} in Linux and {@code "nio"} for other operations
     * systems. Specify the {@code -Dcom.linecorp.armeria.transportType=<nio|epoll|io_uring>} JVM option to
     * override the default.</p>
     */
    public static TransportType transportType() {
        return TRANSPORT_TYPE;
    }

    /**
     * Returns whether the JNI-based TLS support with OpenSSL is enabled. When enabled, Armeria uses OpenSSL
     * for processing TLS connections. When disabled, the current JVM's default {@link SSLEngine} is used
     * instead.
     *
     * <p>This flag is enabled by default for supported platforms. Specify the
     * {@code -Dcom.linecorp.armeria.useOpenSsl=false} JVM option to disable it.
     */
    public static boolean useOpenSsl() {
        if (useOpenSsl != null) {
            return useOpenSsl;
        }
        setUseOpenSslAndDumpOpenSslInfo();
        return useOpenSsl;
    }

    private static void setUseOpenSslAndDumpOpenSslInfo() {
        final boolean useOpenSsl = getBoolean("useOpenSsl", true);
        if (!useOpenSsl) {
            // OpenSSL explicitly disabled
            Flags.useOpenSsl = false;
            dumpOpenSslInfo = false;
            return;
        }
        if (!OpenSsl.isAvailable()) {
            final Throwable cause = Exceptions.peel(OpenSsl.unavailabilityCause());
            logger.info("OpenSSL not available: {}", cause.toString());
            Flags.useOpenSsl = false;
            dumpOpenSslInfo = false;
            return;
        }
        Flags.useOpenSsl = true;
        logger.info("Using OpenSSL: {}, 0x{}", OpenSsl.versionString(),
                    Long.toHexString(OpenSsl.version() & 0xFFFFFFFFL));
        dumpOpenSslInfo = getBoolean("dumpOpenSslInfo", false);
        if (dumpOpenSslInfo) {
            final SSLEngine engine = SslContextUtil.createSslContext(
                    SslContextBuilder::forClient,
                    /* forceHttp1 */ false,
                    /* tlsAllowUnsafeCiphers */ false,
                    ImmutableList.of()).newEngine(ByteBufAllocator.DEFAULT);
            logger.info("All available SSL protocols: {}",
                        ImmutableList.copyOf(engine.getSupportedProtocols()));
            logger.info("Default enabled SSL protocols: {}", SslContextUtil.DEFAULT_PROTOCOLS);
            ReferenceCountUtil.release(engine);
            logger.info("All available SSL ciphers: {}", OpenSsl.availableJavaCipherSuites());
            logger.info("Default enabled SSL ciphers: {}", SslContextUtil.DEFAULT_CIPHERS);
        }
    }

    /**
     * Returns whether information about the OpenSSL environment should be dumped when first starting the
     * application, including supported ciphers.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.dumpOpenSslInfo=true} JVM
     * option to enable it.
     *
     * <p>If {@link #useOpenSsl()} returns {@code false}, this also returns {@code false} no matter you
     * specified the JVM option.
     */
    public static boolean dumpOpenSslInfo() {
        if (dumpOpenSslInfo != null) {
            return dumpOpenSslInfo;
        }
        setUseOpenSslAndDumpOpenSslInfo();
        return dumpOpenSslInfo;
    }

    /**
     * Returns the default server-side maximum number of connections.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#maxNumConnections(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_MAX_NUM_CONNECTIONS}. Specify the
     * {@code -Dcom.linecorp.armeria.maxNumConnections=<integer>} JVM option to override
     * the default value.
     */
    public static int maxNumConnections() {
        return MAX_NUM_CONNECTIONS;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#workerGroup() common worker group} threads.
     * Note that this flag has no effect if a user specified the worker group explicitly via
     * {@link ServerBuilder#workerGroup(EventLoopGroup, boolean)} or
     * {@link ClientFactoryBuilder#workerGroup(EventLoopGroup, boolean)}.
     *
     * <p>The default value of this flag is {@code 2 * <numCpuCores>}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonWorkers=<integer>} JVM option to override the default value.
     */
    public static int numCommonWorkers() {
        return NUM_COMMON_WORKERS;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#blockingTaskExecutor() blocking task executor}
     * threads. Note that this flag has no effect if a user specified the blocking task executor explicitly
     * via {@link ServerBuilder#blockingTaskExecutor(ScheduledExecutorService, boolean)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_NUM_COMMON_BLOCKING_TASK_THREADS}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonBlockingTaskThreads=<integer>} JVM option to override
     * the default value.
     */
    public static int numCommonBlockingTaskThreads() {
        return NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    /**
     * Returns the default server-side maximum length of a request. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ServerBuilder#maxRequestLength(long)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_REQUEST_LENGTH}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxRequestLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxRequestLength() {
        return DEFAULT_MAX_REQUEST_LENGTH;
    }

    /**
     * Returns the default client-side maximum length of a response. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ClientBuilder#maxResponseLength(long)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_RESPONSE_LENGTH}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxResponseLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxResponseLength() {
        return DEFAULT_MAX_RESPONSE_LENGTH;
    }

    /**
     * Returns the default server-side timeout of a request in milliseconds. Note that this flag has no effect
     * if a user specified the value explicitly via {@link ServerBuilder#requestTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultRequestTimeoutMillis=<long>} to override
     * the default value. {@code 0} disables the timeout.
     */
    public static long defaultRequestTimeoutMillis() {
        return DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side timeout of a response in milliseconds. Note that this flag has no effect
     * if a user specified the value explicitly via {@link ClientBuilder#responseTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultResponseTimeoutMillis=<long>} to override
     * the default value. {@code 0} disables the timeout.
     */
    public static long defaultResponseTimeoutMillis() {
        return DEFAULT_RESPONSE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side timeout of a socket connection attempt in milliseconds.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#channelOption(ChannelOption, Object)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_CONNECT_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultConnectTimeoutMillis=<integer>} JVM option to override
     * the default value.
     */
    public static long defaultConnectTimeoutMillis() {
        return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side timeout of a socket write attempt in milliseconds.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientBuilder#writeTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_WRITE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultWriteTimeoutMillis=<integer>} JVM option to override
     * the default value. {@code 0} disables the timeout.
     */
    public static long defaultWriteTimeoutMillis() {
        return DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default server-side idle timeout of a connection for keep-alive in milliseconds.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#idleTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultServerIdleTimeoutMillis=<integer>} JVM option to override
     * the default value.
     */
    public static long defaultServerIdleTimeoutMillis() {
        return DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side idle timeout of a connection for keep-alive in milliseconds.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#idleTimeout(Duration)}.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultClientIdleTimeoutMillis=<integer>} JVM option to override
     * the default value.
     */
    public static long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default maximum length of an HTTP/1 initial line.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http1MaxInitialLineLength(int)} or
     * {@link ClientFactoryBuilder#http1MaxInitialLineLength(int)}.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp1MaxInitialLineLength=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp1MaxInitialLineLength() {
        return DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH;
    }

    /**
     * Returns the default maximum length of all HTTP/1 headers in a request or response.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http1MaxHeaderSize(int)} or
     * {@link ClientFactoryBuilder#http1MaxHeaderSize(int)}.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_HTTP1_MAX_HEADER_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp1MaxHeaderSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp1MaxHeaderSize() {
        return DEFAULT_MAX_HTTP1_HEADER_SIZE;
    }

    /**
     * Returns the default maximum length of each chunk in an HTTP/1 request or response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http1MaxChunkSize(int)} or
     * {@link ClientFactoryBuilder#http1MaxChunkSize(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP1_MAX_CHUNK_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp1MaxChunkSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp1MaxChunkSize() {
        return DEFAULT_HTTP1_MAX_CHUNK_SIZE;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp2Preface(boolean)} option.
     * If enabled, the HTTP/2 connection preface is sent immediately for a cleartext HTTP/2 connection,
     * reducing an extra round trip incurred by the {@code OPTIONS * HTTP/1.1} upgrade request.
     * If disabled, the {@code OPTIONS * HTTP/1.1} request with {@code "Upgrade: h2c"} header is sent for
     * a cleartext HTTP/2 connection. Consider disabling this flag if your HTTP servers have issues
     * handling or rejecting the HTTP/2 connection preface without a upgrade request.
     * Note that this option does not affect ciphertext HTTP/2 connections, which use ALPN for protocol
     * negotiation, and it has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#useHttp2Preface(boolean)}.
     *
     * <p>This flag is enabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp2Preface=false} JVM option to disable it.
     */
    public static boolean defaultUseHttp2Preface() {
        return DEFAULT_USE_HTTP2_PREFACE;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp1Pipelining=true} JVM option to enable it.
     */
    public static boolean defaultUseHttp1Pipelining() {
        return DEFAULT_USE_HTTP1_PIPELINING;
    }

    /**
     * Returns the default value for the PING interval.
     * A <a href="https://datatracker.ietf.org/doc/html/rfc7540#section-6.7">PING</a> frame
     * is sent for HTTP/2 server and client or
     * an <a href="https://datatracker.ietf.org/doc/html/rfc7231#section-4.3.7">OPTIONS</a> request with
     * an asterisk ("*") is sent for HTTP/1 client.
     *
     * <p>Note that this flag is only in effect when {@link #defaultServerIdleTimeoutMillis()} for server and
     * {@link #defaultClientIdleTimeoutMillis()} for client are greater than the value of this flag.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_PING_INTERVAL_MILLIS} milliseconds.
     * Specify the {@code -Dcom.linecorp.armeria.defaultPingIntervalMillis=<integer>} JVM option to override
     * the default value. If the specified value was smaller than 10 seconds, bumps PING interval to 10 seconds.
     */
    public static long defaultPingIntervalMillis() {
        return DEFAULT_PING_INTERVAL_MILLIS;
    }

    /**
     * Returns the server-side maximum allowed number of requests that can be served through one connection.
     *
     * <p>Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#maxNumRequestsPerConnection(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxServerNumRequestsPerConnection=<integer>} JVM option
     * to override the default value. {@code 0} disables the limit.
     */
    public static int defaultMaxServerNumRequestsPerConnection() {
        return DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION;
    }

    /**
     * Returns the client-side maximum allowed number of requests that can be sent through one connection.
     *
     * <p>Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#maxNumRequestsPerConnection(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxClientNumRequestsPerConnection=<integer>} JVM option
     * to override the default value. {@code 0} disables the limit.
     */
    public static int defaultMaxClientNumRequestsPerConnection() {
        return DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION;
    }

    /**
     * Returns the default server-side max age of a connection for keep-alive in milliseconds.
     * If the value of this flag is greater than {@code 0}, a connection is disconnected after the specified
     * amount of the time since the connection was established.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_CONNECTION_AGE_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxServerConnectionAgeMillis=<integer>} JVM option
     * to override the default value. If the specified value was smaller than 1 second,
     * bumps the max connection age to 1 second.
     *
     * @see ServerBuilder#maxConnectionAgeMillis(long)
     */
    public static long defaultMaxServerConnectionAgeMillis() {
        return DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS;
    }

    /**
     * Returns the default client-side max age of a connection for keep-alive in milliseconds.
     * If the value of this flag is greater than {@code 0}, a connection is disconnected after the specified
     * amount of the time since the connection was established.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_CONNECTION_AGE_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxClientConnectionAgeMillis=<integer>} JVM option
     * to override the default value. If the specified value was smaller than 1 second,
     * bumps the max connection age to 1 second.
     *
     * @see ClientFactoryBuilder#maxConnectionAgeMillis(long)
     */
    public static long defaultMaxClientConnectionAgeMillis() {
        return DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2InitialConnectionWindowSize(int)} and
     * {@link ClientFactoryBuilder#http2InitialConnectionWindowSize(int)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2InitialConnectionWindowSize(int)} or
     * {@link ClientFactoryBuilder#http2InitialConnectionWindowSize(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2InitialConnectionWindowSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp2InitialConnectionWindowSize() {
        return DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2InitialStreamWindowSize(int)} and
     * {@link ClientFactoryBuilder#http2InitialStreamWindowSize(int)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2InitialStreamWindowSize(int)} or
     * {@link ClientFactoryBuilder#http2InitialStreamWindowSize(int)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2InitialStreamWindowSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp2InitialStreamWindowSize() {
        return DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2MaxFrameSize(int)} and
     * {@link ClientFactoryBuilder#http2MaxFrameSize(int)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2MaxFrameSize(int)} or {@link ClientFactoryBuilder#http2MaxFrameSize(int)}.
     *
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_MAX_FRAME_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2MaxFrameSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp2MaxFrameSize() {
        return DEFAULT_HTTP2_MAX_FRAME_SIZE;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2MaxStreamsPerConnection(long)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2MaxStreamsPerConnection(long)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2MaxStreamsPerConnection=<integer>} JVM option
     * to override the default value.
     */
    public static long defaultHttp2MaxStreamsPerConnection() {
        return DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2MaxHeaderListSize(long)} and
     * {@link ClientFactoryBuilder#http2MaxHeaderListSize(long)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2MaxHeaderListSize(long)} or
     * {@link ClientFactoryBuilder#http2MaxHeaderListSize(long)}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2MaxHeaderListSize=<integer>} JVM option
     * to override the default value.
     */
    public static long defaultHttp2MaxHeaderListSize() {
        return DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    /**
     * Returns the {@linkplain Backoff#of(String) Backoff specification string} of the default {@link Backoff}
     * returned by {@link Backoff#ofDefault()}. Note that this flag has no effect if a user specified the
     * {@link Backoff} explicitly.
     *
     * <p>The default value of this flag is {@value DEFAULT_DEFAULT_BACKOFF_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultBackoffSpec=<spec>} JVM option to override the default value.
     */
    public static String defaultBackoffSpec() {
        return DEFAULT_BACKOFF_SPEC;
    }

    /**
     * Returns the default maximum number of total attempts. Note that this flag has no effect if a user
     * specified the value explicitly when creating a {@link RetryingClient} or a {@link RetryingRpcClient}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_TOTAL_ATTEMPTS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>} JVM option to
     * override the default value.
     */
    public static int defaultMaxTotalAttempts() {
        return DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the recent
     * request routing history for all {@link Service}s.
     *
     * <p>The default value of this flag is {@value DEFAULT_ROUTE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.routeCache=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.routeCache=maximumSize=4096,expireAfterAccess=600s}.
     * Also, specify {@code -Dcom.linecorp.armeria.routeCache=off} JVM option to disable it.
     */
    @Nullable
    public static String routeCacheSpec() {
        return ROUTE_CACHE_SPEC;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the recent
     * request routing history for all route decorators.
     *
     * <p>The default value of this flag is {@value DEFAULT_ROUTE_DECORATOR_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.routeDecoratorCache=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.routeDecoratorCache=maximumSize=4096,expireAfterAccess=600s}.
     * Also, specify {@code -Dcom.linecorp.armeria.routeDecoratorCache=off} JVM option to disable it.
     */
    @Nullable
    public static String routeDecoratorCacheSpec() {
        return ROUTE_DECORATOR_CACHE_SPEC;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the recent
     * results for parsing a raw HTTP path into a decoded pair of path and query string.
     *
     * <p>The default value of this flag is {@value DEFAULT_PARSED_PATH_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.parsedPathCache=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.parsedPathCache=maximumSize=4096,expireAfterAccess=600s}.
     * Also, specify {@code -Dcom.linecorp.armeria.parsedPathCache=off} JVM option to disable it.
     */
    @Nullable
    public static String parsedPathCacheSpec() {
        return PARSED_PATH_CACHE_SPEC;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the recent
     * results for converting a raw HTTP ASCII header value into a {@link String}. Only the header values
     * whose corresponding header name is listed in {@link #cachedHeaders()} will be cached.
     *
     * <p>The default value of this flag is {@value DEFAULT_HEADER_VALUE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.headerValueCache=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.headerValueCache=maximumSize=4096,expireAfterAccess=600s}.
     * Also, specify {@code -Dcom.linecorp.armeria.headerValueCache=off} JVM option to disable it.
     */
    @Nullable
    public static String headerValueCacheSpec() {
        return HEADER_VALUE_CACHE_SPEC;
    }

    /**
     * Returns the list of HTTP header names whose corresponding values will be cached, as specified in
     * {@link #headerValueCacheSpec()}. Only the header value whose corresponding header name is listed in this
     * flag will be cached. It is not recommended to specify a header with high cardinality, which will defeat
     * the purpose of caching.
     *
     * <p>The default value of this flag is {@value DEFAULT_CACHED_HEADERS}. Specify the
     * {@code -Dcom.linecorp.armeria.cachedHeaders=<comma separated list>} JVM option to override the default.
     */
    public static List<String> cachedHeaders() {
        return CACHED_HEADERS;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the content
     * of the {@link HttpFile}s read by a {@link FileService}. This value is used as the default of
     * {@link FileServiceBuilder#entryCacheSpec(String)}.
     *
     * <p>The default value of this flag is {@value DEFAULT_FILE_SERVICE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.fileServiceCache=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.fileServiceCache=maximumSize=1024,expireAfterAccess=600s}.
     * Also, specify {@code -Dcom.linecorp.armeria.fileServiceCache=off} JVM option to disable it.
     */
    @Nullable
    public static String fileServiceCacheSpec() {
        return FILE_SERVICE_CACHE_SPEC;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the
     * domain names and their resolved addresses. This value is used as the default of
     * {@link DnsResolverGroupBuilder#cacheSpec(String)}.
     *
     * <p>The default value of this flag is {@value DEFAULT_DNS_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.dnsCacheSpec=<spec>} JVM option to override the default value.
     * For example, {@code -Dcom.linecorp.armeria.dnsCacheSpec=maximumSize=1024,expireAfterAccess=600s}.
     *
     * <p>This cache cannot be disabled with {@code "off"} unlike other cache specification flags.
     */
    public static String dnsCacheSpec() {
        return DNS_CACHE_SPEC;
    }

    /**
     * Returns the verbosity of exceptions logged by annotated HTTP services. The value of this property
     * is one of the following:
     * <ul>
     *     <li>{@link ExceptionVerbosity#ALL} - logging all exceptions raised from annotated HTTP services</li>
     *     <li>{@link ExceptionVerbosity#UNHANDLED} - logging exceptions which are not handled by
     *     {@link ExceptionHandler}s provided by a user and are not well-known exceptions
     *     <li>{@link ExceptionVerbosity#NONE} - no logging exceptions</li>
     * </ul>
     * A log message would be written at {@code WARN} level.
     *
     * <p>The default value of this flag is {@value DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY}.
     * Specify the
     * {@code -Dcom.linecorp.armeria.annotatedServiceExceptionVerbosity=<all|unhandled|none>} JVM option
     * to override the default value.
     *
     * @see ExceptionVerbosity
     */
    public static ExceptionVerbosity annotatedServiceExceptionVerbosity() {
        return ANNOTATED_SERVICE_EXCEPTION_VERBOSITY;
    }

    /**
     * Returns the {@link Predicate} that is used to choose the non-loopback IP v4 address in
     * {@link SystemInfo#defaultNonLoopbackIpV4Address()}.
     *
     * <p>The default value of this flag is {@code null}, which means all valid IPv4 addresses are
     * preferred. Specify the {@code -Dcom.linecorp.armeria.preferredIpV4Addresses=<csv>} JVM option
     * to override the default value. The {@code csv} should be
     * <a href="https://datatracker.ietf.org/doc/rfc4632/">Classless Inter-domain Routing(CIDR)</a>s or
     * exact IP addresses separated by commas. For example,
     * {@code -Dcom.linecorp.armeria.preferredIpV4Addresses=211.111.111.111,10.0.0.0/8,192.168.1.0/24}.
     */
    @Nullable
    public static Predicate<InetAddress> preferredIpV4Addresses() {
        return PREFERRED_IP_V4_ADDRESSES;
    }

    /**
     * Enables {@link DefaultAddressResolverGroup} that resolves domain name using JDK's built-in domain name
     * lookup mechanism.
     * Note that JDK's built-in resolver performs a blocking name lookup from the caller thread, and thus
     * this flag should be enabled only when the default asynchronous resolver does not work as expected,
     * for example by always throwing a {@link DnsNameResolverTimeoutException}.
     *
     * <p>This flag is disabled by default.
     * Specify the {@code -Dcom.linecorp.armeria.useJdkDnsResolver=true} JVM option
     * to enable it.
     */
    public static boolean useJdkDnsResolver() {
        return USE_JDK_DNS_RESOLVER;
    }

    /**
     * Returns whether {@link CompletableFuture}s returned by Armeria methods log a warning if
     * {@link CompletableFuture#join()} or {@link CompletableFuture#get()} are called from an event loop thread.
     * Blocking an event loop thread in this manner reduces performance significantly, possibly causing
     * deadlocks, so it should be avoided at all costs (e.g. using {@code thenApply()} type methods to execute
     * asynchronously or running the logic using {@link ServiceRequestContext#blockingTaskExecutor()}.
     *
     * <p>This flag is enabled by default.
     * Specify the {@code -Dcom.linecorp.armeria.reportBlockedEventLoop=false} JVM option
     * to disable it.
     */
    public static boolean reportBlockedEventLoop() {
        return REPORT_BLOCKED_EVENT_LOOP;
    }

    /**
     * Enables validation of HTTP headers for dangerous characters like newlines - such characters can be used
     * for injecting arbitrary content into HTTP responses.
     *
     * <p><strong>DISCLAIMER:</strong> Do not disable this unless you know what you are doing. It is recommended
     * to keep this validation enabled to ensure the sanity of responses. However, you may wish to disable the
     * validation to improve performance when you are sure responses are always safe, for example when only
     * HTTP/2 is used, or when you populate headers with known values, and have no chance of using untrusted
     * ones.
     *
     * <p>See <a href="https://github.com/line/armeria/security/advisories/GHSA-35fr-h7jr-hh86">CWE-113</a> for
     * more details on the security implications of this flag.
     *
     * <p>This flag is enabled by default.
     * Specify the {@code -Dcom.linecorp.armeria.validateHeaders=false} JVM option to disable it.</p>
     */
    public static boolean validateHeaders() {
        return VALIDATE_HEADERS;
    }

    /**
     * Returns whether to allow the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#tlsAllowUnsafeCiphers(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.tlsAllowUnsafeCiphers=true} JVM option to enable it.
     */
    public static boolean tlsAllowUnsafeCiphers() {
        return DEFAULT_TLS_ALLOW_UNSAFE_CIPHERS;
    }

    /**
     * Returns the {@link Set} of {@link TransientServiceOption}s that are enabled for a
     * {@link TransientService}.
     *
     * <p>The default value of this flag is an empty string, which means all
     * {@link TransientServiceOption}s are disabled.
     * Specify the {@code -Dcom.linecorp.armeria.transientServiceOptions=<csv>} JVM option
     * to override the default value. For example,
     * {@code -Dcom.linecorp.armeria.transientServiceOptions=WITH_METRIC_COLLECTION,WITH_ACCESS_LOGGING}.
     */
    public static Set<TransientServiceOption> transientServiceOptions() {
        return TRANSIENT_SERVICE_OPTIONS;
    }

    /**
     * TBU.
     */
    public static int tcpUserTimeoutMillis() {
        return TCP_USER_TIMEOUT_MILLIS;
    }

    /**
     * Returns whether to order route decorators with legacy order that the first decorator is first applied to.
     * For example, if a service and decorators are defined like the followings:
     * <pre>{@code
     * Server server =
     *     Server.builder()
     *           .service("/users", userService)
     *           .decoratorUnder("/", loggingDecorator)
     *           .decoratorUnder("/", authDecorator)
     *           .decoratorUnder("/", traceDecorator)
     *           .build();
     * }</pre>
     * A request will go through the below decorators' order to reach the {@code userService}.
     * {@code request -> loggingDecorator -> authDecorator -> traceDecorator -> userService}
     */
    public static boolean useLegacyRouteDecoratorOrdering() {
        return DEFAULT_USE_LEGACY_ROUTE_DECORATOR_ORDERING;
    }

    @Nullable
    private static String nullableCaffeineSpec(String name, String defaultValue) {
        return caffeineSpec(name, defaultValue, true);
    }

    private static String nonnullCaffeineSpec(String name, String defaultValue) {
        final String spec = caffeineSpec(name, defaultValue, false);
        assert spec != null; // Can never be null if allowOff is false.
        return spec;
    }

    @Nullable
    private static String caffeineSpec(String name, String defaultValue, boolean allowOff) {
        final String spec = get(name, defaultValue, value -> {
            try {
                if ("off".equals(value)) {
                    return allowOff;
                }
                CaffeineSpec.parse(value);
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        if (!"off".equals(spec)) {
            return spec;
        }

        if (allowOff) {
            return null;
        }

        // We specified 'off' as the default value for the flag which can't be 'off'.
        throw new Error();
    }

    private static ExceptionVerbosity exceptionLoggingMode(String name, String defaultValue) {
        final String mode = getNormalized(name, defaultValue,
                                          value -> Arrays.stream(ExceptionVerbosity.values())
                                                         .anyMatch(v -> v.name().equalsIgnoreCase(value)));
        return ExceptionVerbosity.valueOf(mode.toUpperCase());
    }

    private static boolean getBoolean(String name, boolean defaultValue) {
        return getBoolean(name, defaultValue, value -> true);
    }

    private static boolean getBoolean(String name, boolean defaultValue, Predicate<Boolean> validator) {
        return "true".equals(getNormalized(name, String.valueOf(defaultValue), value -> {
            if ("true".equals(value)) {
                return validator.test(true);
            }

            if ("false".equals(value)) {
                return validator.test(false);
            }

            return false;
        }));
    }

    private static int getInt(String name, int defaultValue, IntPredicate validator) {
        return Integer.parseInt(getNormalized(name, String.valueOf(defaultValue), value -> {
            try {
                return validator.test(Integer.parseInt(value));
            } catch (Exception e) {
                // null or non-integer
                return false;
            }
        }));
    }

    private static long getLong(String name, long defaultValue, LongPredicate validator) {
        return Long.parseLong(getNormalized(name, String.valueOf(defaultValue), value -> {
            try {
                return validator.test(Long.parseLong(value));
            } catch (Exception e) {
                // null or non-integer
                return false;
            }
        }));
    }

    private static String get(String name, String defaultValue, Predicate<String> validator) {
        final String fullName = PREFIX + name;
        final String value = System.getProperty(fullName);
        if (value == null) {
            logger.info("{}: {} (default)", fullName, defaultValue);
            return defaultValue;
        }

        if (validator.test(value)) {
            logger.info("{}: {}", fullName, value);
            return value;
        }

        logger.info("{}: {} (default instead of: {})", fullName, defaultValue, value);
        return defaultValue;
    }

    private static String getNormalized(String name, String defaultValue, Predicate<String> validator) {
        final String fullName = PREFIX + name;
        final String value = getLowerCased(fullName);
        if (value == null) {
            logger.info("{}: {} (default)", fullName, defaultValue);
            return defaultValue;
        }

        if (validator.test(value)) {
            logger.info("{}: {}", fullName, value);
            return value;
        }

        logger.info("{}: {} (default instead of: {})", fullName, defaultValue, value);
        return defaultValue;
    }

    @Nullable
    private static String getLowerCased(String fullName) {
        String value = System.getProperty(fullName);
        if (value != null) {
            value = Ascii.toLowerCase(value);
        }
        return value;
    }

    private Flags() {}
}
