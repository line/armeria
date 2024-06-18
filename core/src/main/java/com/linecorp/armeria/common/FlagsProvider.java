/*
 * Copyright 2022 LINE Corporation
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
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;

import com.github.benmanes.caffeine.cache.CaffeineSpec;

import com.linecorp.armeria.client.ClientBuilder;
import com.linecorp.armeria.client.ClientFactoryBuilder;
import com.linecorp.armeria.client.DnsResolverGroupBuilder;
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
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.ServiceWithRoutes;
import com.linecorp.armeria.server.TransientService;
import com.linecorp.armeria.server.TransientServiceOption;
import com.linecorp.armeria.server.file.FileService;
import com.linecorp.armeria.server.file.FileServiceBuilder;
import com.linecorp.armeria.server.file.HttpFile;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.resolver.DefaultAddressResolverGroup;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;

/**
 * A Java SPI (Service Provider Interface) for the {@link Flags} values. Returning null to indicates that this
 * FlagsProvider doesn't provide the flag.
 *
 * <p>Two {@link FlagsProvider}s are provided by default.
 * <ul>
 *     <li>The system property {@link FlagsProvider} which provides value from JVM option</li>
 *     <li>The default {@link FlagsProvider} which provides the default values when flag isn't provides or
 *         fail</li>
 * </ul>
 *
 */
@UnstableApi
@FunctionalInterface
public interface FlagsProvider {

    /**
     * Returns the priority of the {@link FlagsProvider} to determine which implementation to use first.
     * The {@link FlagsProvider} with the highest priority would be used at first. The value could be
     * specified between {@value Integer#MIN_VALUE} and {@value Integer#MAX_VALUE}.
     */
    int priority();

    /**
     * Returns the name of the {@link FlagsProvider} to use for logging.
     */
    default String name() {
        return getClass().getSimpleName();
    }

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
    @Nullable
    default Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return null;
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
    @Nullable
    default Boolean verboseSocketExceptions() {
        return null;
    }

    /**
     * Returns whether the verbose response mode is enabled. When enabled, the server responses will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the server responses will not expose such server-side details to the client.
     *
     * <p>This flag is disabled by default. Specify the {@code -Dcom.linecorp.armeria.verboseResponses=true}
     * JVM option or use {@link ServerBuilder#verboseResponses(boolean)} to enable it.
     */
    @Nullable
    default Boolean verboseResponses() {
        return null;
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
    @Nullable
    default RequestContextStorageProvider requestContextStorageProvider() {
        return null;
    }

    /**
     * Returns whether to log a warning message when any Netty version issues are detected, such as
     * version inconsistencies or missing version information in Netty JARs.
     *
     * <p>The default value of this flag is {@code true}, which means a warning message will be logged
     * if any Netty version issues are detected, which may lead to unexpected behavior. Specify the
     * {@code -Dcom.linecorp.armeria.warnNettyVersions=false} to disable this flag.</p>
     */
    @Nullable
    default Boolean warnNettyVersions() {
        return null;
    }

    /**
     * Returns the {@link TransportType} that will be used for socket I/O in Armeria.
     *
     * <p>The default value of this flag is {@code "epoll"} in Linux and {@code "nio"} for other operations
     * systems. Specify the {@code -Dcom.linecorp.armeria.transportType=<nio|epoll|io_uring>} JVM option to
     * override the default.</p>
     */
    @Nullable
    default TransportType transportType() {
        return null;
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
    @Nullable
    @Deprecated
    default Boolean useOpenSsl() {
        return null;
    }

    /**
     * Returns the {@link TlsEngineType} that will be used for processing TLS connections.
     *
     * <p>The default value of this flag is "openssl", which means the {@link TlsEngineType#OPENSSL} will
     * be used. Specify the {@code -Dcom.linecorp.armeria.tlsEngineType=<jdk|openssl>} JVM option to override
     * the default.</p>
     */
    @Nullable
    @UnstableApi
    default TlsEngineType tlsEngineType() {
        return null;
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
    @Nullable
    default Boolean dumpOpenSslInfo() {
        return null;
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
    @Nullable
    default Integer maxNumConnections() {
        return null;
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
     *
     * @param transportType the {@link TransportType} that will be used for I/O
     */
    @Nullable
    default Integer numCommonWorkers(TransportType transportType) {
        return null;
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
    @Nullable
    default Integer numCommonBlockingTaskThreads() {
        return null;
    }

    /**
     * Returns the default server-side maximum length of a request. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ServerBuilder#maxRequestLength(long)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_REQUEST_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxRequestLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    @Nullable
    default Long defaultMaxRequestLength() {
        return null;
    }

    /**
     * Returns the default client-side maximum length of a response. Note that this flag has no effect if a user
     * specified the value explicitly via {@link ClientBuilder#maxResponseLength(long)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_RESPONSE_LENGTH}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxResponseLength=<long>} to override the default value.
     * {@code 0} disables the length limit.
     */
    @Nullable
    default Long defaultMaxResponseLength() {
        return null;
    }

    /**
     * Returns the default server-side timeout of a request in milliseconds. Note that this flag has no effect
     * if a user specified the value explicitly via {@link ServerBuilder#requestTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_REQUEST_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultRequestTimeoutMillis=<long>} to override
     * the default value. {@code 0} disables the timeout.
     */
    @Nullable
    default Long defaultRequestTimeoutMillis() {
        return null;
    }

    /**
     * Returns the default client-side timeout of a response in milliseconds. Note that this flag has no effect
     * if a user specified the value explicitly via {@link ClientBuilder#responseTimeout(Duration)}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_RESPONSE_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultResponseTimeoutMillis=<long>} to override
     * the default value. {@code 0} disables the timeout.
     */
    @Nullable
    default Long defaultResponseTimeoutMillis() {
        return null;
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
    @Nullable
    default Long defaultConnectTimeoutMillis() {
        return null;
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
    @Nullable
    default Long defaultWriteTimeoutMillis() {
        return null;
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
    @Nullable
    default Long defaultServerIdleTimeoutMillis() {
        return null;
    }

    /**
     * Returns the default option that is preventing the server from staying in an idle state when
     * an HTTP/2 PING frame is received.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_SERVER_KEEP_ALIVE_ON_PING}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultServerKeepAliveOnPing=<boolean>} JVM option to
     * override the default value.
     */
    @Nullable
    default Boolean defaultServerKeepAliveOnPing() {
        return null;
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
    @Nullable
    default Long defaultClientIdleTimeoutMillis() {
        return null;
    }

    /**
     * Returns the default option that is preventing the server from staying in an idle state when
     * an HTTP/2 PING frame is received.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_CLIENT_KEEP_ALIVE_ON_PING}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultClientKeepAliveOnPing=<boolean>} JVM option to
     * override the default value.
     */
    @Nullable
    default Boolean defaultClientKeepAliveOnPing() {
        return null;
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
    @Nullable
    default Integer defaultHttp1MaxInitialLineLength() {
        return null;
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
    @Nullable
    default Integer defaultHttp1MaxHeaderSize() {
        return null;
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
    @Nullable
    default Integer defaultHttp1MaxChunkSize() {
        return null;
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
    @Nullable
    default Boolean defaultUseHttp2Preface() {
        return null;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#preferHttp1(boolean)} option.
     * If enabled, the client will not attempt to upgrade to HTTP/2 for {@link SessionProtocol#HTTP} and
     * {@link SessionProtocol#HTTPS}.
     *
     * <p>Note that this option has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#preferHttp1(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultPreferHttp1=true} JVM option to enable it.
     */
    @UnstableApi
    @Nullable
    default Boolean defaultPreferHttp1() {
        return null;
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
    @Nullable
    @UnstableApi
    default Boolean defaultUseHttp2WithoutAlpn() {
        return null;
    }

    /**
     * Returns the default value of the {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)} option.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#useHttp1Pipelining(boolean)}.
     *
     * <p>This flag is disabled by default. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUseHttp1Pipelining=true} JVM option to enable it.
     */
    @Nullable
    default Boolean defaultUseHttp1Pipelining() {
        return null;
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
    @Nullable
    default Long defaultPingIntervalMillis() {
        return null;
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
    @Nullable
    default Integer defaultMaxServerNumRequestsPerConnection() {
        return null;
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
    @Nullable
    default Integer defaultMaxClientNumRequestsPerConnection() {
        return null;
    }

    /**
     * Returns the default client-side graceful connection shutdown timeout in microseconds.
     *
     * <p>Note that this flag has no effect if a user specified the value explicitly via
     * {@link ClientFactoryBuilder#http2GracefulShutdownTimeoutMillis(long)}.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultClientHttp2GracefulShutdownTimeoutMillis=<long>}
     * JVM option to override the default value. {@code 0} disables the graceful shutdown.
     */
    @Nullable
    default Long defaultClientHttp2GracefulShutdownTimeoutMillis() {
        return null;
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
    @Nullable
    default Long defaultMaxServerConnectionAgeMillis() {
        return null;
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
    @Nullable
    default Long defaultMaxClientConnectionAgeMillis() {
        return null;
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
    @Nullable
    default Long defaultServerConnectionDrainDurationMicros() {
        return null;
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
    @Nullable
    default Integer defaultHttp2InitialConnectionWindowSize() {
        return null;
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
    @Nullable
    default Integer defaultHttp2InitialStreamWindowSize() {
        return null;
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
    @Nullable
    default Integer defaultHttp2MaxFrameSize() {
        return null;
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
    @Nullable
    default Long defaultHttp2MaxStreamsPerConnection() {
        return null;
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
    @Nullable
    default Long defaultHttp2MaxHeaderListSize() {
        return null;
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
    @Nullable
    default Integer defaultServerHttp2MaxResetFramesPerMinute() {
        return null;
    }

    /**
     * Returns the {@linkplain Backoff#of(String) Backoff specification string} of the default {@link Backoff}
     * returned by {@link Backoff#ofDefault()}. Note that this flag has no effect if a user specified the
     * {@link Backoff} explicitly.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_BACKOFF_SPEC}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultBackoffSpec=<spec>} JVM option to override the default value.
     */
    @Nullable
    default String defaultBackoffSpec() {
        return null;
    }

    /**
     * Returns the default maximum number of total attempts. Note that this flag has no effect if a user
     * specified the value explicitly when creating a {@link RetryingClient} or a {@link RetryingRpcClient}.
     *
     * <p>The default value of this flag is {@value DefaultFlagsProvider#DEFAULT_MAX_TOTAL_ATTEMPTS}.
     * Specify the {@code -Dcom.linecorp.armeria.defaultMaxTotalAttempts=<integer>} JVM option to
     * override the default value.
     */
    @Nullable
    default Integer defaultMaxTotalAttempts() {
        return null;
    }

    /**
     * Returns the amount of time to wait by default before aborting an {@link HttpRequest} when
     * its corresponding {@link HttpResponse} is complete.
     * Note that this flag has no effect if a user specified the value explicitly via
     * {@link ServerBuilder#requestAutoAbortDelayMillis(long)} or
     * {@link ClientBuilder#requestAutoAbortDelayMillis(long)}.
     */
    @UnstableApi
    @Nullable
    default Long defaultRequestAutoAbortDelayMillis() {
        return null;
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
    default String routeCacheSpec() {
        return null;
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
    default String routeDecoratorCacheSpec() {
        return null;
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
    default String parsedPathCacheSpec() {
        return null;
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
    default String headerValueCacheSpec() {
        return null;
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
    @Nullable
    default List<String> cachedHeaders() {
        return null;
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
    default String fileServiceCacheSpec() {
        return null;
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
    @Nullable
    default String dnsCacheSpec() {
        return null;
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
    @Nullable
    default Predicate<InetAddress> preferredIpV4Addresses() {
        return null;
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
    @Nullable
    default Boolean useJdkDnsResolver() {
        return null;
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
    @Nullable
    default Boolean reportBlockedEventLoop() {
        return null;
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
    @Nullable
    default Boolean reportMaskedRoutes() {
        return null;
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
    @Nullable
    default Boolean validateHeaders() {
        return null;
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
    @Nullable
    default Boolean tlsAllowUnsafeCiphers() {
        return null;
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
    @Nullable
    default Integer defaultMaxClientHelloLength() {
        return null;
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
    @Nullable
    default Set<TransientServiceOption> transientServiceOptions() {
        return null;
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
    @Nullable
    default Boolean useDefaultSocketOptions() {
        return null;
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
    @Nullable
    default Boolean useLegacyRouteDecoratorOrdering() {
        return null;
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
    @Nullable
    default Boolean allowDoubleDotsInQueryString() {
        return null;
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
    @Nullable
    default Boolean allowSemicolonInPathComponent() {
        return null;
    }

    /**
     * Returns the {@link Path} that is used to store the files uploaded from {@code multipart/form-data}
     * requests.
     */
    @Nullable
    default Path defaultMultipartUploadsLocation() {
        return null;
    }

    /**
     * Returns the {@link MultipartRemovalStrategy} that is used to determine how to remove the uploaded files
     * from {@code multipart/form-data}.
     */
    @Nullable
    default MultipartRemovalStrategy defaultMultipartRemovalStrategy() {
        return null;
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
    @Nullable
    default Sampler<? super RequestContext> requestContextLeakDetectionSampler() {
        return null;
    }

    /**
     * Returns the {@link MeterRegistry} where armeria records metrics to by default.
     *
     * <p>The default value of this flag is {@link Metrics#globalRegistry}.
     */
    @Nullable
    @UnstableApi
    default MeterRegistry meterRegistry() {
        return null;
    }

    /**
     * Returns the default interval in milliseconds between the reports on unhandled exceptions.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUnhandledExceptionsReportIntervalMillis=<long>} JVM option to
     * override the default value.</p>
     *
     * @deprecated Use {@link #defaultUnloggedExceptionsReportIntervalMillis()} instead.
     */
    @Nullable
    @Deprecated
    default Long defaultUnhandledExceptionsReportIntervalMillis() {
        return null;
    }

    /**
     * Returns the default interval in milliseconds between the reports on unhandled exceptions.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultUnloggedExceptionsReportIntervalMillis=<long>} JVM option to
     * override the default value.</p>
     */
    @Nullable
    @UnstableApi
    default Long defaultUnloggedExceptionsReportIntervalMillis() {
        return null;
    }

    /**
     * Returns the {@link DistributionStatisticConfig} where armeria utilizes.
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
    @Nullable
    @UnstableApi
    default DistributionStatisticConfig distributionStatisticConfig() {
        return null;
    }

    /**
     * Returns the default time in milliseconds to wait before closing an HTTP/1 connection when a server needs
     * to close the connection. This allows to avoid a server socket from remaining in the TIME_WAIT state
     * instead of CLOSED when a connection is closed.
     *
     * <p>The default value of this flag is
     * {@value DefaultFlagsProvider#DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS}. Specify the
     * {@code -Dcom.linecorp.armeria.defaultHttp1ConnectionCloseDelayMillis=<long>} JVM option to
     * override the default value. {@code 0} closes the connection immediately. </p>
     */
    @Nullable
    @UnstableApi
    default Long defaultHttp1ConnectionCloseDelayMillis() {
        return null;
    }
}
