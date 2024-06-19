/*
 * Copyright 2017 LINE Corporation
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
package com.linecorp.armeria.common;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.DnsResolverGroupBuilder;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.common.FlagsLoaded;
import com.linecorp.armeria.internal.common.util.SslContextUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerErrorHandler;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceWithRoutes;
import com.linecorp.armeria.server.TransientService;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.FileServiceBuilder;
import com.linecorp.armeria.server.file.HttpFile;
import com.linecorp.armeria.server.logging.LoggingService;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
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
 * The system properties that affect Armeria's runtime behavior. The priority or each flag is determined
 * by {@link FlagsProvider#priority()}. On each flag, if value is fail to validated. The next candidate will be
 * used.
 */
public final class Flags {

    private static final Logger logger = LoggerFactory.getLogger(Flags.class);

    private static final String PREFIX = "com.linecorp.armeria.";

    private static final List<FlagsProvider> FLAGS_PROVIDERS;

    static {
        final List<FlagsProvider> flagsProviders =
                ImmutableList.copyOf(ServiceLoader.load(FlagsProvider.class, Flags.class.getClassLoader()))
                             .stream()
                             .sorted(Comparator.comparingInt(FlagsProvider::priority).reversed())
                             .collect(Collectors.toList());
        flagsProviders.add(0, SystemPropertyFlagsProvider.INSTANCE);
        FLAGS_PROVIDERS = ImmutableList.copyOf(flagsProviders);
    }

    private static final Sampler<Class<? extends Throwable>> VERBOSE_EXCEPTION_SAMPLER =
            getValue(FlagsProvider::verboseExceptionSampler, "verboseExceptionSampler");

    private static final String VERBOSE_EXCEPTION_SAMPLER_SPEC;

    private static final long DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS;

    static {
        final String strSpec = getNormalized("verboseExceptions",
                                             DefaultFlagsProvider.VERBOSE_EXCEPTION_SAMPLER_SPEC, val -> {
                    try {
                        Sampler.of(val);
                        return true;
                    } catch (Exception e) {
                        // Invalid sampler specification
                        return false;
                    }
                });
        if ("true".equals(strSpec)) {
            VERBOSE_EXCEPTION_SAMPLER_SPEC = "always";
        } else if ("false".equals(strSpec)) {
            VERBOSE_EXCEPTION_SAMPLER_SPEC = "never";
        } else {
            VERBOSE_EXCEPTION_SAMPLER_SPEC = strSpec;
        }

        final Long intervalMillis = getUserValue(
                FlagsProvider::defaultUnloggedExceptionsReportIntervalMillis,
                "defaultUnloggedExceptionsReportIntervalMillis", value -> value >= 0);
        if (intervalMillis != null) {
            DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS = intervalMillis;
        } else {
            DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS =
                    getValue(FlagsProvider::defaultUnhandledExceptionsReportIntervalMillis,
                             "defaultUnhandledExceptionsReportIntervalMillis", value -> value >= 0);
        }
    }

    private static final Predicate<InetAddress> PREFERRED_IP_V4_ADDRESSES =
            getValue(FlagsProvider::preferredIpV4Addresses, "preferredIpV4Addresses");

    private static final boolean VERBOSE_SOCKET_EXCEPTIONS =
            getValue(FlagsProvider::verboseSocketExceptions, "verboseSocketExceptions");

    private static final boolean VERBOSE_RESPONSES =
            getValue(FlagsProvider::verboseResponses, "verboseResponses");

    private static final RequestContextStorageProvider REQUEST_CONTEXT_STORAGE_PROVIDER =
            getValue(FlagsProvider::requestContextStorageProvider, "requestContextStorageProvider");

    private static final boolean WARN_NETTY_VERSIONS =
            getValue(FlagsProvider::warnNettyVersions, "warnNettyVersions");

    private static final boolean DEFAULT_USE_EPOLL = TransportType.EPOLL.isAvailable();
    private static final boolean USE_EPOLL = getBoolean("useEpoll",
                                                        DEFAULT_USE_EPOLL,
                                                        value -> TransportType.EPOLL.isAvailable() || !value);

    private static final Predicate<TransportType> TRANSPORT_TYPE_VALIDATOR = transportType -> {
        switch (transportType) {
            case IO_URING:
                return validateTransportType(TransportType.IO_URING, "io_uring");
            case KQUEUE:
                return validateTransportType(TransportType.KQUEUE, "Kqueue");
            case EPOLL:
                return validateTransportType(TransportType.EPOLL, "/dev/epoll");
            case NIO:
                return true;
            default:
                return false;
        }
    };

    private static boolean validateTransportType(TransportType transportType, String friendlyName) {
        if (transportType.isAvailable()) {
            logger.info("Using {}", friendlyName);
            return true;
        } else {
            final Throwable cause = transportType.unavailabilityCause();
            if (cause != null) {
                logger.info("{} not available: {}", friendlyName, cause.toString());
            } else {
                logger.info("{} not available: ?", friendlyName);
            }
            return false;
        }
    }

    private static final TransportType TRANSPORT_TYPE =
            getValue(FlagsProvider::transportType, "transportType", TRANSPORT_TYPE_VALIDATOR);

    @Nullable
    private static TlsEngineType tlsEngineType;

    @Nullable
    private static Boolean dumpOpenSslInfo;

    private static final int MAX_NUM_CONNECTIONS =
            getValue(FlagsProvider::maxNumConnections, "maxNumConnections", value -> value > 0);

    private static final int NUM_COMMON_WORKERS =
            getValue(provider -> provider.numCommonWorkers(TRANSPORT_TYPE),
                     "numCommonWorkers", value -> value > 0);

    private static final int NUM_COMMON_BLOCKING_TASK_THREADS =
            getValue(FlagsProvider::numCommonBlockingTaskThreads, "numCommonBlockingTaskThreads",
                     value -> value > 0);

    private static final long DEFAULT_MAX_REQUEST_LENGTH =
            getValue(FlagsProvider::defaultMaxRequestLength, "defaultMaxRequestLength",
                     value -> value >= 0);

    private static final long DEFAULT_MAX_RESPONSE_LENGTH =
            getValue(FlagsProvider::defaultMaxResponseLength, "defaultMaxResponseLength",
                     value -> value >= 0);

    private static final long DEFAULT_REQUEST_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultRequestTimeoutMillis, "defaultRequestTimeoutMillis",
                     value -> value >= 0);

    private static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultResponseTimeoutMillis, "defaultResponseTimeoutMillis",
                     value -> value >= 0);

    private static final long DEFAULT_CONNECT_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultConnectTimeoutMillis, "defaultConnectTimeoutMillis",
                     value -> value > 0);

    private static final long DEFAULT_WRITE_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultWriteTimeoutMillis, "defaultWriteTimeoutMillis",
                     value -> value >= 0);

    private static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultServerIdleTimeoutMillis, "defaultServerIdleTimeoutMillis",
                     value -> value >= 0);

    private static final boolean DEFAULT_SERVER_KEEP_ALIVE_ON_PING =
            getValue(FlagsProvider::defaultServerKeepAliveOnPing, "defaultServerKeepAliveOnPing");

    private static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultClientIdleTimeoutMillis, "defaultClientIdleTimeoutMillis",
                     value -> value >= 0);

    private static final boolean DEFAULT_CLIENT_KEEP_ALIVE_ON_PING =
            getValue(FlagsProvider::defaultClientKeepAliveOnPing, "defaultClientKeepAliveOnPing");

    private static final long DEFAULT_PING_INTERVAL_MILLIS =
            getValue(FlagsProvider::defaultPingIntervalMillis, "defaultPingIntervalMillis",
                     value -> value >= 0);

    private static final int DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION =
            getValue(FlagsProvider::defaultMaxServerNumRequestsPerConnection,
                     "defaultMaxServerNumRequestsPerConnection", value -> value >= 0);

    private static final int DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION =
            getValue(FlagsProvider::defaultMaxClientNumRequestsPerConnection,
                     "defaultMaxClientNumRequestsPerConnection", value -> value >= 0);

    private static final long DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS =
            getValue(FlagsProvider::defaultMaxServerConnectionAgeMillis,
                     "defaultMaxServerConnectionAgeMillis", value -> value >= 0);

    private static final long DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS =
            getValue(FlagsProvider::defaultMaxClientConnectionAgeMillis,
                     "defaultMaxClientConnectionAgeMillis", value -> value >= 0);

    private static final long DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS =
            getValue(FlagsProvider::defaultServerConnectionDrainDurationMicros,
                     "defaultServerConnectionDrainDurationMicros", value -> value >= 0);

    private static final long DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS =
            getValue(FlagsProvider::defaultClientHttp2GracefulShutdownTimeoutMillis,
                     "defaultClientHttp2GracefulShutdownTimeoutMillis", value -> value >= 0);

    private static final int DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE =
            getValue(FlagsProvider::defaultHttp2InitialConnectionWindowSize,
                     "defaultHttp2InitialConnectionWindowSize", value -> value > 0);

    private static final int DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE =
            getValue(FlagsProvider::defaultHttp2InitialStreamWindowSize,
                     "defaultHttp2InitialStreamWindowSize", value -> value > 0);

    private static final int DEFAULT_HTTP2_MAX_FRAME_SIZE =
            getValue(FlagsProvider::defaultHttp2MaxFrameSize, "defaultHttp2MaxFrameSize",
                     value -> value >= Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND &&
                              value <= Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND);

    private static final long DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION =
            getValue(FlagsProvider::defaultHttp2MaxStreamsPerConnection, "defaultHttp2MaxStreamsPerConnection",
                     value -> value > 0 && value <= 0xFFFFFFFFL);

    private static final long DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE =
            getValue(FlagsProvider::defaultHttp2MaxHeaderListSize, "defaultHttp2MaxHeaderListSize",
                     value -> value > 0 && value <= 0xFFFFFFFFL);

    private static final int DEFAULT_SERVER_HTTP2_MAX_RESET_FRAMES_PER_MINUTE =
            getValue(FlagsProvider::defaultServerHttp2MaxResetFramesPerMinute,
                     "defaultServerHttp2MaxResetFramesPerMinute", value -> value >= 0);

    private static final int DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH =
            getValue(FlagsProvider::defaultHttp1MaxInitialLineLength, "defaultHttp1MaxInitialLineLength",
                     value -> value >= 0);

    private static final int DEFAULT_MAX_HTTP1_HEADER_SIZE =
            getValue(FlagsProvider::defaultHttp1MaxHeaderSize,
                     "defaultHttp1MaxHeaderSize", value -> value >= 0);

    private static final int DEFAULT_HTTP1_MAX_CHUNK_SIZE =
            getValue(FlagsProvider::defaultHttp1MaxChunkSize,
                     "defaultHttp1MaxChunkSize", value -> value >= 0);

    private static final boolean DEFAULT_USE_HTTP2_PREFACE =
            getValue(FlagsProvider::defaultUseHttp2Preface, "defaultUseHttp2Preface");

    private static final boolean DEFAULT_PREFER_HTTP1 =
            getValue(FlagsProvider::defaultPreferHttp1, "defaultPreferHttp1");

    private static final boolean DEFAULT_USE_HTTP2_WITHOUT_ALPN =
            getValue(FlagsProvider::defaultUseHttp2WithoutAlpn, "defaultUseHttp2WithoutAlpn");

    private static final boolean DEFAULT_USE_HTTP1_PIPELINING =
            getValue(FlagsProvider::defaultUseHttp1Pipelining, "defaultUseHttp1Pipelining");

    private static final String DEFAULT_BACKOFF_SPEC =
            getValue(FlagsProvider::defaultBackoffSpec, "defaultBackoffSpec", value -> {
                try {
                    Backoff.of(value);
                    return true;
                } catch (Exception e) {
                    // Invalid backoff specification
                    return false;
                }
            });

    private static final int DEFAULT_MAX_TOTAL_ATTEMPTS =
            getValue(FlagsProvider::defaultMaxTotalAttempts, "defaultMaxTotalAttempts", value -> value > 0);

    private static final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS =
            getValue(FlagsProvider::defaultRequestAutoAbortDelayMillis, "defaultRequestAutoAbortDelayMillis");

    @Nullable
    private static final String ROUTE_CACHE_SPEC =
            nullableCaffeineSpec(FlagsProvider::routeCacheSpec, "routeCacheSpec");

    @Nullable
    private static final String ROUTE_DECORATOR_CACHE_SPEC =
            nullableCaffeineSpec(FlagsProvider::routeDecoratorCacheSpec, "routeDecoratorCacheSpec");

    @Nullable
    private static final String PARSED_PATH_CACHE_SPEC =
            nullableCaffeineSpec(FlagsProvider::parsedPathCacheSpec, "parsedPathCacheSpec");

    @Nullable
    private static final String HEADER_VALUE_CACHE_SPEC =
            nullableCaffeineSpec(FlagsProvider::headerValueCacheSpec, "headerValueCacheSpec");

    private static final List<String> CACHED_HEADERS =
            getValue(FlagsProvider::cachedHeaders, "cachedHeaders",
                     list -> list.stream().allMatch(CharMatcher.ascii()::matchesAllOf));

    @Nullable
    private static final String FILE_SERVICE_CACHE_SPEC =
            nullableCaffeineSpec(FlagsProvider::fileServiceCacheSpec, "fileServiceCacheSpec");

    private static final String DNS_CACHE_SPEC =
            nonnullCaffeineSpec(FlagsProvider::dnsCacheSpec, "dnsCacheSpec");

    private static final String DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY = "unhandled";
    private static final ExceptionVerbosity ANNOTATED_SERVICE_EXCEPTION_VERBOSITY =
            exceptionLoggingMode("annotatedServiceExceptionVerbosity",
                                 DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY);

    private static final boolean USE_JDK_DNS_RESOLVER =
            getValue(FlagsProvider::useJdkDnsResolver, "useJdkDnsResolver");

    private static final boolean REPORT_BLOCKED_EVENT_LOOP =
            getValue(FlagsProvider::reportBlockedEventLoop, "reportBlockedEventLoop");

    private static final boolean REPORT_MASKED_ROUTES =
            getValue(FlagsProvider::reportMaskedRoutes, "reportMaskedRoutes");

    private static final boolean VALIDATE_HEADERS =
            getValue(FlagsProvider::validateHeaders, "validateHeaders");

    private static final boolean TLS_ALLOW_UNSAFE_CIPHERS =
            getValue(FlagsProvider::tlsAllowUnsafeCiphers, "tlsAllowUnsafeCiphers");

    // Maximum 16MiB https://datatracker.ietf.org/doc/html/rfc5246#section-7.4
    private static final int DEFAULT_MAX_CLIENT_HELLO_LENGTH =
            getValue(FlagsProvider::defaultMaxClientHelloLength, "defaultMaxClientHelloLength",
                     value -> value >= 0 && value <= 16777216); // 16MiB

    private static final Set<TransientServiceOption> TRANSIENT_SERVICE_OPTIONS =
            getValue(FlagsProvider::transientServiceOptions, "transientServiceOptions");

    private static final boolean USE_LEGACY_ROUTE_DECORATOR_ORDERING =
            getValue(FlagsProvider::useLegacyRouteDecoratorOrdering, "useLegacyRouteDecoratorOrdering");

    private static final boolean USE_DEFAULT_SOCKET_OPTIONS =
            getValue(FlagsProvider::useDefaultSocketOptions, "useDefaultSocketOptions");

    private static final boolean ALLOW_DOUBLE_DOTS_IN_QUERY_STRING =
            getValue(FlagsProvider::allowDoubleDotsInQueryString, "allowDoubleDotsInQueryString");

    private static final boolean ALLOW_SEMICOLON_IN_PATH_COMPONENT =
            getValue(FlagsProvider::allowSemicolonInPathComponent, "allowSemicolonInPathComponent");

    private static final Path DEFAULT_MULTIPART_UPLOADS_LOCATION =
            getValue(FlagsProvider::defaultMultipartUploadsLocation, "defaultMultipartUploadsLocation");

    private static final MultipartRemovalStrategy DEFAULT_MULTIPART_REMOVAL_STRATEGY =
            getValue(FlagsProvider::defaultMultipartRemovalStrategy, "defaultMultipartRemovalStrategy");

    private static final Sampler<? super RequestContext> REQUEST_CONTEXT_LEAK_DETECTION_SAMPLER =
            getValue(FlagsProvider::requestContextLeakDetectionSampler, "requestContextLeakDetectionSampler");

    private static final MeterRegistry METER_REGISTRY =
            getValue(FlagsProvider::meterRegistry, "meterRegistry");

    private static final DistributionStatisticConfig DISTRIBUTION_STATISTIC_CONFIG =
            getValue(FlagsProvider::distributionStatisticConfig, "distributionStatisticConfig");

    private static final long DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS =
            getValue(FlagsProvider::defaultHttp1ConnectionCloseDelayMillis,
                    "defaultHttp1ConnectionCloseDelayMillis", value -> value >= 0);

    /**
     * Returns the specification of the {@link Sampler} that determines whether to retain the stack
     * trace of the exceptions that are thrown frequently by Armeria. A sampled exception will have the stack
     * trace while the others will have an empty stack trace to eliminate the cost of capturing the stack
     * trace.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#VERBOSE_EXCEPTION_SAMPLER_SPEC},
     * which retains the stack trace of the exceptions at the maximum rate of 10 exceptions/sec.
     * Specify the {@code -Dcom.linecorp.armeria.verboseExceptions=<specification>} JVM option to override
     * the default. See {@link Sampler#of(String)} for the specification string format.</p>
     */
    public static Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return VERBOSE_EXCEPTION_SAMPLER;
    }

    /**
     * Returns the specification string of the {@link Sampler} that determines whether to retain the
     * stack trace of the exceptions that are thrown frequently by Armeria.
     *
     * @see #verboseExceptionSampler()
     * @deprecated Use {@link #verboseExceptionSampler()} and
     *             {@code -Dcom.linecorp.armeria.verboseExceptions=<specification>}.
     */
    @Deprecated
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
     * Returns the {@link RequestContextStorageProvider} that provides the {@link RequestContextStorage}.
     *
     * <p>By default, If no {@link RequestContextStorageProvider} SPI provider implementation is provided,
     * This flag returns {@link RequestContextStorageProvider} that provides
     * {@link RequestContextStorage#threadLocal()}. Otherwise, the first {@link RequestContextStorageProvider}
     * SPI provider implementation will be selected.</p>
     *
     * <p>By specifying the {@code -Dcom.linecorp.armeria.requestContextStorageProvider=<FQCN>} JVM option, you
     * are able to select which {@link RequestContextStorageProvider} SPI provider implementation to used.
     * If none of them matches, the next {@link FlagsProvider#requestContextStorageProvider()} will be
     * selected.</p>
     */
    public static RequestContextStorageProvider requestContextStorageProvider() {
        return REQUEST_CONTEXT_STORAGE_PROVIDER;
    }

    /**
     * Returns whether to log a warning message when any Netty version issues are detected, such as
     * version inconsistencies or missing version information in Netty JARs.
     *
     * <p>The default value of this flag is {@code true}, which means a warning message will be logged
     * if any Netty version issues are detected, which may lead to unexpected behavior. Specify the
     * {@code -Dcom.linecorp.armeria.warnNettyVersions=false} to disable this flag.</p>
     */
    public static boolean warnNettyVersions() {
        return WARN_NETTY_VERSIONS;
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
     *
     * @deprecated Use {@link #tlsEngineType()} and {@code -Dcom.linecorp.armeria.tlsEngineType=openssl}.
     */
    @Deprecated
    public static boolean useOpenSsl() {
        return tlsEngineType() == TlsEngineType.OPENSSL;
    }

    /**
     * Returns the {@link TlsEngineType} that will be used for processing TLS connections.
     *
     * <p>The default value of this flag is {@link TlsEngineType#OPENSSL}.
     * Specify the {@code -Dcom.linecorp.armeria.tlsEngineType=<jdk|openssl>} JVM option to override
     * the default value.
     */
    @UnstableApi
    public static TlsEngineType tlsEngineType() {
        if (tlsEngineType != null) {
            return tlsEngineType;
        }
        detectTlsEngineAndDumpOpenSslInfo();
        return tlsEngineType;
    }

    private static void detectTlsEngineAndDumpOpenSslInfo() {

        final Boolean useOpenSsl = getUserValue(FlagsProvider::useOpenSsl, "useOpenSsl",
                                                ignored -> true);
        final TlsEngineType tlsEngineTypeValue = getUserValue(FlagsProvider::tlsEngineType,
                                                              "tlsEngineType", ignored -> true);

        if (useOpenSsl != null && (useOpenSsl != (tlsEngineTypeValue == TlsEngineType.OPENSSL))) {
            logger.warn("useOpenSsl({}) and tlsEngineType({}) are incompatible, tlsEngineType will be used",
                        useOpenSsl, tlsEngineTypeValue);
        }

        TlsEngineType preferredTlsEngineType = null;
        if (tlsEngineTypeValue != null) {
            preferredTlsEngineType = tlsEngineTypeValue;
        } else if (useOpenSsl != null) {
            preferredTlsEngineType = useOpenSsl ? TlsEngineType.OPENSSL : TlsEngineType.JDK;
        }
        if (preferredTlsEngineType == TlsEngineType.OPENSSL) {
            if (!OpenSsl.isAvailable()) {
                final Throwable cause = Exceptions.peel(OpenSsl.unavailabilityCause());
                logger.info("OpenSSL not available: {}", cause.toString());
                preferredTlsEngineType = TlsEngineType.JDK;
            }
        }
        if (preferredTlsEngineType == null) {
            preferredTlsEngineType = OpenSsl.isAvailable() ? TlsEngineType.OPENSSL : TlsEngineType.JDK;
        }
        tlsEngineType = preferredTlsEngineType;

        if (tlsEngineType != TlsEngineType.OPENSSL) {
            dumpOpenSslInfo = false;
            logger.info("Using TLS engine: {}", tlsEngineType);
            return;
        }

        logger.info("Using Tls engine: OpenSSL {}, 0x{}", OpenSsl.versionString(),
                    Long.toHexString(OpenSsl.version() & 0xFFFFFFFFL));
        dumpOpenSslInfo = getValue(FlagsProvider::dumpOpenSslInfo, "dumpOpenSslInfo");
        if (dumpOpenSslInfo) {
            final SSLEngine engine = SslContextUtil.createSslContext(
                    SslContextBuilder::forClient,
                    /* forceHttp1 */ false,
                    tlsEngineType,
                    /* tlsAllowUnsafeCiphers */ false,
                    ImmutableList.of(), null).newEngine(ByteBufAllocator.DEFAULT);
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
     * <p>If {@link #tlsEngineType()} does not return {@link TlsEngineType#OPENSSL}, this also returns
     * {@code false} no matter what the specified JVM option is.
     */
    public static boolean dumpOpenSslInfo() {
        if (dumpOpenSslInfo != null) {
            return dumpOpenSslInfo;
        }
        detectTlsEngineAndDumpOpenSslInfo();
        return dumpOpenSslInfo;
    }

    /**
     * Returns the default server-side maximum number of connections.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#maxNumConnections(int)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#MAX_NUM_CONNECTIONS}. Specify the
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
     * <p>The default value of this flag is {@code 2 * <numCpuCores>} for {@link TransportType#NIO},
     * {@link TransportType#EPOLL} and {@link TransportType#KQUEUE} and {@code <numCpuCores>} for
     * {@link TransportType#IO_URING}. Specify the {@code -Dcom.linecorp.armeria.numCommonWorkers=<integer>}
     * JVM option to override the default value.
     */
    public static int numCommonWorkers() {
        return NUM_COMMON_WORKERS;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#blockingTaskExecutor() blocking task executor}
     * threads. Note that this flag has no effect if a user specified the blocking task executor explicitly
     * via {@link ServerBuilder#blockingTaskExecutor(ScheduledExecutorService, boolean)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#NUM_COMMON_BLOCKING_TASK_THREADS}.
     * Specify the {@code -Dcom.linecorp.armeria.numCommonBlockingTaskThreads=<integer>} JVM option
     * to override the default value.
     */
    public static int numCommonBlockingTaskThreads() {
        return NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    /**
     * Returns the default server-side maximum length of a request. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ServerBuilder#maxRequestLength(long)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_REQUEST_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxRequestLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxRequestLength() {
        return DEFAULT_MAX_REQUEST_LENGTH;
    }

    /**
     * Returns the default client-side maximum length of a response. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ClientBuilder#maxResponseLength(long)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_RESPONSE_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxResponseLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxResponseLength() {
        return DEFAULT_MAX_RESPONSE_LENGTH;
    }

    /**
     * Returns the default server-side timeout of a request in milliseconds. Note that this flag has no effect
     * if a user specified the value explicitly via {@link ServerBuilder#requestTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_REQUEST_TIMEOUT_MILLIS}.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_CONNECT_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultConnectTimeoutMillis=<integer>} JVM option to override
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_WRITE_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultWriteTimeoutMillis=<integer>} JVM option to override
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultServerIdleTimeoutMillis=<integer>} JVM option to
     * override the default value.
     */
    public static long defaultServerIdleTimeoutMillis() {
        return DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default option that is preventing the server from staying in an idle state when
     * an HTTP/2 PING frame is received.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_SERVER_KEEP_ALIVE_ON_PING}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultServerKeepAliveOnPing=<boolean>} JVM option to
     * override the default value.
     */
    public static boolean defaultServerKeepAliveOnPing() {
        return DEFAULT_SERVER_KEEP_ALIVE_ON_PING;
    }

    /**
     * Returns the default client-side idle timeout of a connection for keep-alive in milliseconds.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#idleTimeout(Duration)}.
     *
     * <p>This default value of this flag is {@value DefaultFlagsProvider#DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultClientIdleTimeoutMillis=<integer>} JVM option to
     * override the default value.
     */
    public static long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default option that is preventing the server from staying in an idle state when
     * an HTTP/2 PING frame is received.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_CLIENT_KEEP_ALIVE_ON_PING}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultClientKeepAliveOnPing=<boolean>} JVM option to
     * override the default value.
     */
    public static boolean defaultClientKeepAliveOnPing() {
        return DEFAULT_CLIENT_KEEP_ALIVE_ON_PING;
    }

    /**
     * Returns the default maximum length of an HTTP/1 initial line.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http1MaxInitialLineLength(int)} or
     * {@link ClientFactoryBuilder#http1MaxInitialLineLength(int)}.
     *
     * <p>This default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH}.
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
     * <p>This default value of this flag is {@value DefaultFlagsProvider#DEFAULT_HTTP1_MAX_HEADER_SIZE}.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_HTTP1_MAX_CHUNK_SIZE}.
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
     *
     * <p>Note that this option is only effective when the {@link SessionProtocol} of the {@link Endpoint} is
     * {@link SessionProtocol#HTTP}. This option does not affect ciphertext HTTP/2 connections, which use ALPN
     * for protocol negotiation, or {@link SessionProtocol#H2C}, which will always use HTTP/2 connection
     * preface. This option is ignored if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#useHttp2Preface(boolean)}.
     *
     * <p>This flag is enabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp2Preface=false} JVM option to disable it.
     */
    public static boolean defaultUseHttp2Preface() {
        return DEFAULT_USE_HTTP2_PREFACE;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#preferHttp1(boolean)} option.
     * If enabled, the client will not attempt to upgrade to HTTP/2 for {@link SessionProtocol#HTTP} and
     * {@link SessionProtocol#HTTPS}. However, the client will use HTTP/2 if {@link SessionProtocol#H2} or
     * {@link SessionProtocol#H2C} is used.
     *
     * <p>Note that this option has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#preferHttp1(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultPreferHttp1=true} JVM option to enable it.
     */
    @UnstableApi
    public static boolean defaultPreferHttp1() {
        return DEFAULT_PREFER_HTTP1;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp2WithoutAlpn(boolean)} option.
     * If enabled, even when ALPN negotiation fails client will try to attempt upgrade to HTTP/2 when needed.
     * This will be either HTTP/2 connection preface or HTTP/1-to-2 upgrade request,
     * depending on {@link ClientFactoryBuilder#useHttp2Preface(boolean)} setting.
     * If disabled, when ALPN negotiation fails client will also fail in case HTTP/2 was required.
     * {@link ClientFactoryBuilder#useHttp2WithoutAlpn(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp2WithoutAlpn=true} JVM option to enable it.
     */
    @UnstableApi
    public static boolean defaultUseHttp2WithoutAlpn() {
        return DEFAULT_USE_HTTP2_WITHOUT_ALPN;
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_PING_INTERVAL_MILLIS}
     * milliseconds. Specify the {@code -Dcom.linecorp.armeria.defaultPingIntervalMillis=<integer>} JVM option
     * to override the default value. If the specified value was smaller than 10 seconds, bumps PING
     * interval to 10 seconds.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION}.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION}.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS}.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS}.
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
     * Returns the default server-side graceful connection shutdown drain duration in microseconds.
     * If the value of this flag is greater than {@code 0}, a connection shutdown will have a drain period
     * when client will be notified about the shutdown, but in flight requests will still be accepted.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultServerConnectionDrainDurationMicros=<long>}
     * JVM option to override the default value.
     *
     * <p>
     * At the beginning of the drain period server signals the clients that the connection shutdown is imminent
     * but still accepts in flight requests.
     * After the drain period end server stops accepting new requests.
     * </p>
     *
     * <p>
     * Note that HTTP/1 doesn't support draining as described here, so for HTTP/1 drain period microseconds
     * is always {@code 0}, which means the connection will be closed immediately as soon as
     * the current in-progress request is handled.
     * </p>
     *
     * @see ServerBuilder#connectionDrainDuration(Duration)
     * @see ServerBuilder#connectionDrainDurationMicros(long)
     */
    public static long defaultServerConnectionDrainDurationMicros() {
        return DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS;
    }

    /**
     * Returns the default client-side graceful connection shutdown timeout in milliseconds.
     * {@code 0} disables the timeout and closes the connection immediately after sending a GOAWAY frame.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultClientHttp2GracefulShutdownTimeoutMillis=<long>}
     * JVM option to override the default value.
     * After the drain period end client will close all the connections.
     * </p>
     *
     * @see ClientFactoryBuilder#http2GracefulShutdownTimeout(Duration)
     * @see ClientFactoryBuilder#http2GracefulShutdownTimeoutMillis(long)
     */
    public static long defaultClientHttp2GracefulShutdownTimeoutMillis() {
        return DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2InitialConnectionWindowSize(int)} and
     * {@link ClientFactoryBuilder#http2InitialConnectionWindowSize(int)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2InitialConnectionWindowSize(int)} or
     * {@link ClientFactoryBuilder#http2InitialConnectionWindowSize(int)}.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE}.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE}.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_HTTP2_MAX_FRAME_SIZE}.
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
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION}.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2MaxHeaderListSize=<integer>} JVM option
     * to override the default value.
     */
    public static long defaultHttp2MaxHeaderListSize() {
        return DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    /**
     * Returns the default maximum number of RST frames that are allowed per window before the connection is
     * closed. This allows to protect against the remote peer flooding us with such frames and using up a lot
     * of CPU. Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#http2MaxResetFramesPerWindow(int, int)}.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_SERVER_HTTP2_MAX_RESET_FRAMES_PER_MINUTE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultServerHttp2MaxResetFramesPerMinute=<integer>} JVM option
     * to override the default value. {@code 0} means no protection should be applied.
     */
    @UnstableApi
    public static int defaultServerHttp2MaxResetFramesPerMinute() {
        return DEFAULT_SERVER_HTTP2_MAX_RESET_FRAMES_PER_MINUTE;
    }

    /**
     * Returns the {@linkplain Backoff#of(String) Backoff specification string} of the default {@link Backoff}
     * returned by {@link Backoff#ofDefault()}. Note that this flag has no effect if a user specified the
     * {@link Backoff} explicitly.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_BACKOFF_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultBackoffSpec=<spec>} JVM option to override the default value.
     */
    public static String defaultBackoffSpec() {
        return DEFAULT_BACKOFF_SPEC;
    }

    /**
     * Returns the default maximum number of total attempts. Note that this flag has no effect if a user
     * specified the value explicitly when creating a {@link RetryingClient} or a {@link RetryingRpcClient}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_TOTAL_ATTEMPTS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>} JVM option to
     * override the default value.
     */
    public static int defaultMaxTotalAttempts() {
        return DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    /**
     * Returns the amount of time to wait by default before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#requestAutoAbortDelayMillis(long)} (long)} or
     * {@link ClientBuilder#requestAutoAbortDelayMillis(long)}.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultRequestAutoAbortDelayMillis=<long>} JVM option
     * to override the default value.
     */
    @UnstableApi
    public static long defaultRequestAutoAbortDelayMillis() {
        return DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS;
    }

    /**
     * Returns the {@linkplain CaffeineSpec Caffeine specification string} of the cache that stores the recent
     * request routing history for all {@link Service}s.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#ROUTE_CACHE_SPEC}. Specify the
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#ROUTE_DECORATOR_CACHE_SPEC}. Specify
     * the {@code -Dcom.linecorp.armeria.routeDecoratorCache=<spec>} JVM option to override the default value.
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#PARSED_PATH_CACHE_SPEC}. Specify the
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#HEADER_VALUE_CACHE_SPEC}. Specify the
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#CACHED_HEADERS}. Specify the
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#FILE_SERVICE_CACHE_SPEC}. Specify the
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
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DNS_CACHE_SPEC}. Specify the
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
     * @deprecated Use {@link LoggingService} or log exceptions using
     *             {@link ServerBuilder#errorHandler(ServerErrorHandler)}.
     */
    @Deprecated
    public static ExceptionVerbosity annotatedServiceExceptionVerbosity() {
        return ANNOTATED_SERVICE_EXCEPTION_VERBOSITY;
    }

    /**
     * Returns the {@link Predicate} that is used to choose the non-loopback IP v4 address in
     * {@link SystemInfo#defaultNonLoopbackIpV4Address()}.
     *
     * <p>This flag by default returns a {@link Predicate} that always returns {@code true},
     * which means all valid IPv4 addresses are preferred.
     * Specify the {@code -Dcom.linecorp.armeria.preferredIpV4Addresses=<csv>} JVM option to override the
     * default value. The {@code csv} should be
     * <a href="https://datatracker.ietf.org/doc/rfc4632/">Classless Inter-domain Routing(CIDR)</a>s or
     * exact IP addresses separated by commas. For example,
     * {@code -Dcom.linecorp.armeria.preferredIpV4Addresses=211.111.111.111,10.0.0.0/8,192.168.1.0/24}.
     */
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
     * Returns whether to log a warning if a {@link ServiceWithRoutes} is added to a {@link ServerBuilder}
     * using the methods that requires a path pattern, such as
     * {@link ServerBuilder#service(String, HttpService)}. For example, the following code will mask the
     * returned route ({@code "/foo"}) in favor of the specified route ({@code "/bar"}):
     * <pre>{@code
     * > HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
     * >     @Override
     * >     public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) { ... }
     * >
     * >     @Override
     * >     public Set<Route> routes() {
     * >         return Set.of(Route.builder().path("/foo").build());
     * >     }
     * > };
     * >
     * > Server.builder()
     * >       .service("/bar", serviceWithRoutes)
     * >       .build();
     * }</pre>
     */
    public static boolean reportMaskedRoutes() {
        return REPORT_MASKED_ROUTES;
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
        return TLS_ALLOW_UNSAFE_CIPHERS;
    }

    /**
     * Returns the default maximum client hello length that a server allows.
     * The length shouldn't exceed 16MiB as described in
     * <a href="https://datatracker.ietf.org/doc/html/rfc5246#section-7.4">Handshake Protocol</a>.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_CLIENT_HELLO_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxClientHelloLength=<integer>} JVM option to
     * override the default value.
     */
    public static int defaultMaxClientHelloLength() {
        return DEFAULT_MAX_CLIENT_HELLO_LENGTH;
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
     * Returns whether default socket options defined by Armeria are enabled.
     * If enabled, the following socket options are set automatically when
     * {@code /dev/epoll} or {@code io_uring} is in use:
     * <ul>
     *   <li>TCP_USER_TIMEOUT</li>
     *   <li>TCP_KEEPIDLE</li>
     *   <li>TCP_KEEPINTVL</li>
     * </ul>
     *
     * <p>This flag is enabled by default.
     * Specify the {@code -Dcom.linecorp.armeria.useDefaultSocketOptions=false}
     * JVM option to disable it.</p>
     */
    public static boolean useDefaultSocketOptions() {
        return USE_DEFAULT_SOCKET_OPTIONS;
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
        return USE_LEGACY_ROUTE_DECORATOR_ORDERING;
    }

    /**
     * Returns the {@link Path} that is used to store the files uploaded from {@code multipart/form-data}
     * requests.
     */
    public static Path defaultMultipartUploadsLocation() {
        return DEFAULT_MULTIPART_UPLOADS_LOCATION;
    }

    /**
     * Returns the {@link MultipartRemovalStrategy} that is used to determine how to remove the uploaded files
     * from {@code multipart/form-data}.
     */
    @UnstableApi
    public static MultipartRemovalStrategy defaultMultipartRemovalStrategy() {
        return DEFAULT_MULTIPART_REMOVAL_STRATEGY;
    }

    /**
     * Returns whether to allow double dots ({@code ..}) in a request path query string.
     *
     * <p>Note that double dots in a query string can lead to a vulnerability if a query param value contains
     * an improper path such as {@code /download?path=../../secrets.txt}. Therefore, extra caution should be
     * taken when enabling this option, and you may need additional validations at the application level.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.allowDoubleDotsInQueryString=true} JVM option to enable it.
     */
    public static boolean allowDoubleDotsInQueryString() {
        return ALLOW_DOUBLE_DOTS_IN_QUERY_STRING;
    }

    /**
     * Returns whether to allow a semicolon ({@code ;}) in a request path component on the server-side.
     * If disabled, the substring from the semicolon to before the next slash, commonly referred to as
     * matrix variables, is removed. For example, {@code /foo;a=b/bar} will be converted to {@code /foo/bar}.
     * Also, an exception is raised if a semicolon is used for binding a service. For example, the following
     * code raises an exception:
     * <pre>{@code
     * Server server =
     *    Server.builder()
     *      .service("/foo;bar", ...)
     *      .build();
     * }</pre>
     * Note that this flag has no effect on the client-side.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.allowSemicolonInPathComponent=true} JVM option to enable it.
     */
    public static boolean allowSemicolonInPathComponent() {
        return ALLOW_SEMICOLON_IN_PATH_COMPONENT;
    }

    /**
     * Returns the {@link Sampler} that determines whether to trace the stack trace of request contexts leaks
     * and how frequently to keeps stack trace. A sampled exception will have the stack trace while the others
     * will have an empty stack trace to eliminate the cost of capturing the stack trace.
     *
     * <p>The default value of this flag is {@link Sampler#never()}.
     * Specify the {@code -Dcom.linecorp.armeria.requestContextLeakDetectionSampler=<specification>} JVM option
     * to override the default. This feature is disabled if {@link Sampler#never()} is specified.
     * See {@link Sampler#of(String)} for the specification string format.</p>
     */
    @UnstableApi
    public static Sampler<? super RequestContext> requestContextLeakDetectionSampler() {
        return REQUEST_CONTEXT_LEAK_DETECTION_SAMPLER;
    }

    /**
     * Returns the {@link MeterRegistry} where armeria records metrics to by default.
     *
     * <p>The default value of this flag is {@link Metrics#globalRegistry}.
     */
    @UnstableApi
    public static MeterRegistry meterRegistry() {
        return METER_REGISTRY;
    }

    /**
     * Returns the default interval in milliseconds between the reports on unlogged exceptions.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUnhandledExceptionsReportIntervalMillis=<long>} JVM option to
     * override the default value.</p>
     *
     * @deprecated Use {@link #defaultUnloggedExceptionsReportIntervalMillis()} instead.
     */
    @Deprecated
    public static long defaultUnhandledExceptionsReportIntervalMillis() {
        return DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS;
    }

    /**
     * Returns the default interval in milliseconds between the reports on unlogged exceptions.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUnloggedExceptionsReportIntervalMillis=<long>} JVM option to
     * override the default value.</p>
     */
    @UnstableApi
    public static long defaultUnloggedExceptionsReportIntervalMillis() {
        return DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS;
    }

    /**
     * Returns the default {@link DistributionStatisticConfig} of the {@link Timer}s and
     * {@link DistributionSummary}s created by Armeria.
     *
     * <p>The default value of this flag is as follows:
     * <pre>{@code
     * DistributionStatisticConfig.builder()
     *     .percentilesHistogram(false)
     *     .serviceLevelObjectives()
     *     .percentiles(
     *          0, 0.5, 0.75, 0.9, 0.95, 0.98, 0.99, 0.999, 1.0)
     *     .percentilePrecision(2)
     *     .minimumExpectedValue(1.0)
     *     .maximumExpectedValue(Double.MAX_VALUE)
     *     .expiry(Duration.ofMinutes(3))
     *     .bufferLength(3)
     *     .build();
     * }</pre>
     */
    @UnstableApi
    public static DistributionStatisticConfig distributionStatisticConfig() {
        return DISTRIBUTION_STATISTIC_CONFIG;
    }

    /**
     * Returns the default time in milliseconds to wait before closing an HTTP/1 connection when a server needs
     * to close the connection. This allows to avoid a server socket from remaining in the TIME_WAIT state
     * instead of CLOSED when a connection is closed.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultHttp1ConnectionCloseDelayMillis=<long>} JVM option to
     * override the default value. {@code 0} closes the connection immediately.</p>
     */
    @UnstableApi
    public static long defaultHttp1ConnectionCloseDelayMillis() {
        return DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS;
    }

    @Nullable
    private static String nullableCaffeineSpec(Function<FlagsProvider, String> method, String flagName) {
        return caffeineSpec(method, flagName, true);
    }

    private static String nonnullCaffeineSpec(Function<FlagsProvider, String> method, String flagName) {
        final String spec = caffeineSpec(method, flagName, false);
        assert spec != null; // Can never be null if allowOff is false.
        return spec;
    }

    @Nullable
    private static String caffeineSpec(Function<FlagsProvider, String> method, String flagName,
                                       boolean allowOff) {
        final String spec = getValue(method, flagName, value -> {
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
        return ExceptionVerbosity.valueOf(Ascii.toUpperCase(mode));
    }

    private static boolean getBoolean(String name, Boolean defaultValue, Predicate<Boolean> validator) {
        final Predicate<String> combinedValidator = value -> {
            if ("true".equals(value)) {
                return validator.test(true);
            }

            if ("false".equals(value)) {
                return validator.test(false);
            }
            return false;
        };
        return Boolean.valueOf(getNormalized(name, defaultValue.toString(), combinedValidator));
    }

    private static String getNormalized(String name, String defaultValue,
                                        Predicate<String> validator) {
        final String fullName = PREFIX + name;
        String value = System.getProperty(fullName);
        if (value != null) {
            value = Ascii.toLowerCase(value);
        }

        if (value != null) {
            if (validator.test(value)) {
                logger.info("{}: {} (sysprops)", name, value);
                return value;
            }
            logger.warn("{}: {} (sysprops, validation failed)", name, value);
        }
        logger.info("{}: {} (default)", name, defaultValue);
        return defaultValue;
    }

    private static <T> T getValue(Function<FlagsProvider, @Nullable T> method, String flagName) {
        return getValue(method, flagName, unused -> true);
    }

    private static <T> T getValue(Function<FlagsProvider, @Nullable T> method,
                                  String flagName, Predicate<T> validator) {
        final T t = getUserValue(method, flagName, validator);
        if (t != null) {
            return t;
        }

        return method.apply(DefaultFlagsProvider.INSTANCE);
    }

    @Nullable
    private static <T> T getUserValue(Function<FlagsProvider, @Nullable T> method, String flagName,
                                      Predicate<T> validator) {
        for (FlagsProvider provider : FLAGS_PROVIDERS) {
            try {
                final T value = method.apply(provider);
                if (value == null) {
                    continue;
                }

                if (!validator.test(value)) {
                    logger.warn("{}: {} ({}, validation failed)", flagName, value, provider.name());
                    continue;
                }

                logger.info("{}: {} ({})", flagName, value, provider.name());
                return value;
            } catch (Exception ex) {
                logger.warn("{}: ({}, {})", flagName, provider.name(), ex.getMessage());
            }
        }

        return null;
    }

    private Flags() {}

    // This static block is defined at the end of this file deliberately
    // to ensure that all static variables defined beforehand are initialized.
    static {
        FlagsLoaded.set();
    }
}
