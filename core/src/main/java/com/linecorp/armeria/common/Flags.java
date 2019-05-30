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

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

import javax.annotation.Nullable;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.CaffeineSpec;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.client.retry.RetryingHttpClient;
import com.linecorp.armeria.client.retry.RetryingRpcClient;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.SslContextUtil;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.annotation.ExceptionHandler;
import com.linecorp.armeria.server.annotation.ExceptionVerbosity;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.epoll.Epoll;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.ssl.OpenSsl;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.ReferenceCountUtil;

/**
 * The system properties that affect Armeria's runtime behavior.
 */
public final class Flags {

    private static final Logger logger = LoggerFactory.getLogger(Flags.class);

    private static final Splitter CSV_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private static final String PREFIX = "com.linecorp.armeria.";

    private static final int NUM_CPU_CORES = Runtime.getRuntime().availableProcessors();

    private static final boolean VERBOSE_EXCEPTIONS = getBoolean("verboseExceptions", false);

    private static final boolean VERBOSE_SOCKET_EXCEPTIONS = getBoolean("verboseSocketExceptions", false);

    private static final boolean VERBOSE_RESPONSES = getBoolean("verboseResponses", false);

    private static final boolean HAS_WSLENV = System.getenv("WSLENV") != null;
    private static final boolean USE_EPOLL = getBoolean("useEpoll", isEpollAvailable(),
                                                        value -> isEpollAvailable() || !value);

    private static final boolean USE_OPENSSL = getBoolean("useOpenSsl", OpenSsl.isAvailable(),
                                                          value -> OpenSsl.isAvailable() || !value);

    private static final boolean DUMP_OPENSSL_INFO = getBoolean("dumpOpenSslInfo", false);

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
    private static final Optional<String> ROUTE_CACHE_SPEC =
            caffeineSpec("routeCache", DEFAULT_ROUTE_CACHE_SPEC);

    private static final String DEFAULT_COMPOSITE_SERVICE_CACHE_SPEC = "maximumSize=256";
    private static final Optional<String> COMPOSITE_SERVICE_CACHE_SPEC =
            caffeineSpec("compositeServiceCache", DEFAULT_COMPOSITE_SERVICE_CACHE_SPEC);

    private static final String DEFAULT_PARSED_PATH_CACHE_SPEC = "maximumSize=4096";
    private static final Optional<String> PARSED_PATH_CACHE_SPEC =
            caffeineSpec("parsedPathCache", DEFAULT_PARSED_PATH_CACHE_SPEC);

    private static final String DEFAULT_HEADER_VALUE_CACHE_SPEC = "maximumSize=4096";
    private static final Optional<String> HEADER_VALUE_CACHE_SPEC =
            caffeineSpec("headerValueCache", DEFAULT_HEADER_VALUE_CACHE_SPEC);

    private static final String DEFAULT_CACHED_HEADERS =
            ":authority,:scheme,:method,accept-encoding,content-type";
    private static final List<String> CACHED_HEADERS =
            CSV_SPLITTER.splitToList(getNormalized(
                    "cachedHeaders", DEFAULT_CACHED_HEADERS, CharMatcher.ascii()::matchesAllOf));

    private static final String DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY = "unhandled";
    private static final ExceptionVerbosity ANNOTATED_SERVICE_EXCEPTION_VERBOSITY =
            exceptionLoggingMode("annotatedServiceExceptionVerbosity",
                                 DEFAULT_ANNOTATED_SERVICE_EXCEPTION_VERBOSITY);

    static {
        if (!isEpollAvailable()) {
            final Throwable cause = Epoll.unavailabilityCause();
            if (cause != null) {
                logger.info("/dev/epoll not available: {}", Exceptions.peel(cause).toString());
            } else {
                if (HAS_WSLENV) {
                    logger.info("/dev/epoll not available: WSL not supported");
                } else {
                    logger.info("/dev/epoll not available: ?");
                }
            }
        } else if (USE_EPOLL) {
            logger.info("Using /dev/epoll");
        }

        if (!OpenSsl.isAvailable()) {
            final Throwable cause = Exceptions.peel(OpenSsl.unavailabilityCause());
            logger.info("OpenSSL not available: {}", cause.toString());
        } else if (USE_OPENSSL) {
            logger.info("Using OpenSSL: {}, 0x{}",
                        OpenSsl.versionString(),
                        Long.toHexString(OpenSsl.version() & 0xFFFFFFFFL));

            if (dumpOpenSslInfo()) {
                final SSLEngine engine = SslContextUtil.createSslContext(
                        SslContextBuilder::forClient,
                        false,
                        unused -> {}).newEngine(ByteBufAllocator.DEFAULT);
                logger.info("All available SSL protocols: {}",
                            ImmutableList.copyOf(engine.getSupportedProtocols()));
                logger.info("Default enabled SSL protocols: {}", SslContextUtil.DEFAULT_PROTOCOLS);
                ReferenceCountUtil.release(engine);
                logger.info("All available SSL ciphers: {}", OpenSsl.availableJavaCipherSuites());
                logger.info("Default enabled SSL ciphers: {}", SslContextUtil.DEFAULT_CIPHERS);
            }
        }
    }

    private static boolean isEpollAvailable() {
        // Netty epoll transport does not work with WSL (Windows Sybsystem for Linux) yet.
        // TODO(trustin): Re-enable on WSL if https://github.com/Microsoft/WSL/issues/1982 is resolved.
        return Epoll.isAvailable() && !HAS_WSLENV;
    }

    /**
     * Returns whether the verbose exception mode is enabled. When enabled, the exceptions frequently thrown by
     * Armeria will have full stack trace. When disabled, such exceptions will have empty stack trace to
     * eliminate the cost of capturing the stack trace.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.verboseExceptions=true}
     * JVM option to enable it.
     */
    public static boolean verboseExceptions() {
        return VERBOSE_EXCEPTIONS;
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
     * Returns whether the JNI-based {@code /dev/epoll} socket I/O is enabled. When enabled on Linux, Armeria
     * uses {@code /dev/epoll} directly for socket I/O. When disabled, {@code java.nio} socket API is used
     * instead.
     *
     * <p>This flag is enabled by default for supported platforms. Specify the
     * {@code -Dcom.linecorp.armeria.useEpoll=false} JVM option to disable it.
     */
    public static boolean useEpoll() {
        return USE_EPOLL;
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
        return USE_OPENSSL;
    }

    /**
     * Returns whether information about the OpenSSL environment should be dumped when first starting the
     * application, including supported ciphers.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.dumpOpenSslInfo=true} JVM
     * option to enable it.
     */
    public static boolean dumpOpenSslInfo() {
        return DUMP_OPENSSL_INFO;
    }

    /**
     * Returns the default server-side maximum number of connections.
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
     * Note that this value has effect only if a user did not specify a worker group.
     *
     * <p>The default value of this flag is {@code 2 * <numCpuCores>}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonWorkers=<integer>} JVM option to override the default value.
     */
    public static int numCommonWorkers() {
        return NUM_COMMON_WORKERS;
    }

    /**
     * Returns the default number of {@linkplain CommonPools#blockingTaskExecutor() blocking task executor}
     * threads. Note that this value has effect only if a user did not specify a blocking task executor.
     *
     * <p>The default value of this flag is {@value #DEFAULT_NUM_COMMON_BLOCKING_TASK_THREADS}. Specify the
     * {@code -Dcom.linecorp.armeria.numCommonBlockingTaskThreads=<integer>} JVM option to override
     * the default value.
     */
    public static int numCommonBlockingTaskThreads() {
        return NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    /**
     * Returns the default server-side maximum length of a request. Note that this value has effect
     * only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_REQUEST_LENGTH}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxRequestLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxRequestLength() {
        return DEFAULT_MAX_REQUEST_LENGTH;
    }

    /**
     * Returns the default client-side maximum length of a response. Note that this value has effect
     * only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_RESPONSE_LENGTH}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxResponseLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    public static long defaultMaxResponseLength() {
        return DEFAULT_MAX_RESPONSE_LENGTH;
    }

    /**
     * Returns the default server-side timeout of a request in milliseconds. Note that this value has effect
     * only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_REQUEST_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultRequestTimeoutMillis=<long>} to override
     * the default value. {@code 0} disables the timeout.
     */
    public static long defaultRequestTimeoutMillis() {
        return DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default client-side timeout of a response in milliseconds. Note that this value has effect
     * only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultClientIdleTimeoutMillis=<integer>} JVM option to override
     * the default value.
     */
    public static long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Returns the default maximum length of an HTTP/1 response initial line.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp1MaxInitialLineLength=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp1MaxInitialLineLength() {
        return DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH;
    }

    /**
     * Returns the default maximum length of all headers in an HTTP/1 response.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This default value of this flag is {@value #DEFAULT_DEFAULT_HTTP1_MAX_HEADER_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp1MaxHeaderSize=<integer>} JVM option
     * to override the default value.
     */
    public static int defaultHttp1MaxHeaderSize() {
        return DEFAULT_MAX_HTTP1_HEADER_SIZE;
    }

    /**
     * Returns the default maximum length of each chunk in an HTTP/1 response content.
     * The content or a chunk longer than this value will be split into smaller chunks
     * so that their lengths never exceed it.
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This flag is enabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp2Preface=false} JVM option to disable it.
     */
    public static boolean defaultUseHttp2Preface() {
        return DEFAULT_USE_HTTP2_PREFACE;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)} option.
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp1Pipelining=true} JVM option to enable it.
     */
    public static boolean defaultUseHttp1Pipelining() {
        return DEFAULT_USE_HTTP1_PIPELINING;
    }

    /**
     * Returns the default value of the {@link ServerBuilder#http2InitialConnectionWindowSize(int)} and
     * {@link ClientFactoryBuilder#http2InitialConnectionWindowSize(int)} option.
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
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
     * Note that this value has effect only if a user did not specify it.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultHttp2MaxHeaderListSize=<integer>} JVM option
     * to override the default value.
     */
    public static long defaultHttp2MaxHeaderListSize() {
        return DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    /**
     * Returns the default value of the {@code backoffSpec} parameter when instantiating a {@link Backoff}
     * using {@link Backoff#of(String)}. Note that this value has effect only if a user did not specify the
     * {@code defaultBackoffSpec} in the constructor call.
     *
     * <p>The default value of this flag is {@value DEFAULT_DEFAULT_BACKOFF_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultBackoffSpec=<spec>} JVM option to override the default value.
     */
    public static String defaultBackoffSpec() {
        return DEFAULT_BACKOFF_SPEC;
    }

    /**
     * Returns the default maximum number of total attempts. Note that this value has effect only if a user
     * did not specify it when creating a {@link RetryingHttpClient} or a {@link RetryingRpcClient}.
     *
     * <p>The default value of this flag is {@value #DEFAULT_DEFAULT_MAX_TOTAL_ATTEMPTS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>} JVM option to
     * override the default value.
     */
    public static int defaultMaxTotalAttempts() {
        return DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    /**
     * Returns the value of the {@code routeCache} parameter. It would be used to create a Caffeine
     * {@link Cache} instance using {@link Caffeine#from(String)} for routing a request. The {@link Cache}
     * would hold the mappings of {@link RoutingContext} and the designated {@link ServiceConfig}
     * for a request to improve server performance.
     *
     * <p>The default value of this flag is {@value DEFAULT_ROUTE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.routeCache=<spec>} JVM option to override the default value.
     * Also, specify {@code -Dcom.linecorp.armeria.routeCache=off} JVM option to disable it.
     */
    public static Optional<String> routeCacheSpec() {
        return ROUTE_CACHE_SPEC;
    }

    /**
     * Returns the value of the {@code parsedPathCache} parameter. It would be used to create a Caffeine
     * {@link Cache} instance using {@link Caffeine#from(String)} mapping raw HTTP paths to parsed pair of
     * path and query, after validation.
     *
     * <p>The default value of this flag is {@value DEFAULT_PARSED_PATH_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.parsedPathCache=<spec>} JVM option to override the default value.
     * Also, specify {@code -Dcom.linecorp.armeria.parsedPathCache=off} JVM option to disable it.
     */
    public static Optional<String> parsedPathCacheSpec() {
        return PARSED_PATH_CACHE_SPEC;
    }

    /**
     * Returns the value of the {@code headerValueCache} parameter. It would be used to create a Caffeine
     * {@link Cache} instance using {@link Caffeine#from(String)} mapping raw HTTP ascii header values to
     * {@link String}.
     *
     * <p>The default value of this flag is {@value DEFAULT_HEADER_VALUE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.headerValueCache=<spec>} JVM option to override the default value.
     * Also, specify {@code -Dcom.linecorp.armeria.headerValueCache=off} JVM option to disable it.
     */
    public static Optional<String> headerValueCacheSpec() {
        return HEADER_VALUE_CACHE_SPEC;
    }

    /**
     * Returns the value of the {@code cachedHeaders} parameter which contains a comma-separated list of
     * headers whose values are cached using {@code headerValueCache}.
     *
     * <p>The default value of this flag is {@value DEFAULT_CACHED_HEADERS}. Specify the
     * {@code -Dcom.linecorp.armeria.cachedHeaders=<csv>} JVM option to override the default value.
     */
    public static List<String> cachedHeaders() {
        return CACHED_HEADERS;
    }

    /**
     * Returns the value of the {@code compositeServiceCache} parameter. It would be used to create a
     * Caffeine {@link Cache} instance using {@link Caffeine#from(String)} for routing a request.
     * The {@link Cache} would hold the mappings of {@link RoutingContext} and the designated
     * {@link ServiceConfig} for a request to improve server performance.
     *
     * <p>The default value of this flag is {@value DEFAULT_COMPOSITE_SERVICE_CACHE_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.compositeServiceCache=<spec>} JVM option to override the default value.
     * Also, specify {@code -Dcom.linecorp.armeria.compositeServiceCache=off} JVM option to disable it.
     */
    public static Optional<String> compositeServiceCacheSpec() {
        return COMPOSITE_SERVICE_CACHE_SPEC;
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

    private static Optional<String> caffeineSpec(String name, String defaultValue) {
        final String spec = get(name, defaultValue, value -> {
            try {
                if (!"off".equals(value)) {
                    CaffeineSpec.parse(value);
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        });
        return "off".equals(spec) ? Optional.empty()
                                  : Optional.of(spec);
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
