/*
 *  Copyright 2022 LINE Corporation
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

import java.io.File;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.TransientServiceOption;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;

/**
 * Implementation of {@link FlagsProvider} which provides default values to {@link Flags}.
 */
final class DefaultFlagsProvider implements FlagsProvider {

    static final DefaultFlagsProvider INSTANCE = new DefaultFlagsProvider();

    static final String VERBOSE_EXCEPTION_SAMPLER_SPEC = "rate-limit=10";
    static final Sampler<Class<? extends Throwable>>
            VERBOSE_EXCEPTION_SAMPLER = new ExceptionSampler(VERBOSE_EXCEPTION_SAMPLER_SPEC);

    static final int MAX_NUM_CONNECTIONS = Integer.MAX_VALUE;
    static final int NUM_COMMON_BLOCKING_TASK_THREADS = 200; // from Tomcat default maxThreads
    static final long DEFAULT_MAX_REQUEST_LENGTH = 10 * 1024 * 1024; // 10 MiB
    static final long DEFAULT_MAX_RESPONSE_LENGTH = 10 * 1024 * 1024; // 10 MiB

    // Use slightly greater value than the client-side default so that clients close the connection more often.
    static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS = 15000; // 15 seconds
    static final boolean DEFAULT_SERVER_KEEP_ALIVE_ON_PING = false;
    static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS = 10000; // 10 seconds
    static final boolean DEFAULT_CLIENT_KEEP_ALIVE_ON_PING = false;
    static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 3200; // 3.2 seconds
    static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 1000; // 1 second

    // Use the fragmentation size as the default. https://datatracker.ietf.org/doc/html/rfc5246#section-6.2.1
    static final int DEFAULT_MAX_CLIENT_HELLO_LENGTH = 16384; // 16KiB

    // Use slightly greater value than the default request timeout so that clients have a higher chance of
    // getting proper 503 Service Unavailable response when server-side timeout occurs.
    static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = 15 * 1000; // 15 seconds
    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10 * 1000; // 10 seconds
    static final int DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH = 4096; // from Netty
    static final int DEFAULT_HTTP1_MAX_HEADER_SIZE = 8192; // from Netty
    static final int DEFAULT_HTTP1_MAX_CHUNK_SIZE = 8192; // from Netty
    static final long DEFAULT_PING_INTERVAL_MILLIS = 0; // Disabled
    static final int DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled
    static final int DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled
    static final long DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS = 0; // Disabled
    static final long DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS = 0; // Disabled
    static final long DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS = 1000000;
    // Same as server connection drain duration
    static final long DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS = 1000;
    static final int DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE = 1024 * 1024; // 1MiB
    static final int DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE = 1024 * 1024; // 1MiB
    static final int DEFAULT_HTTP2_MAX_FRAME_SIZE = 16384; // From HTTP/2 specification

    // Can't use 0xFFFFFFFFL because some implementations use a signed 32-bit integer to store HTTP/2 SETTINGS
    // parameter values, thus anything greater than 0x7FFFFFFF will break them or make them unhappy.
    static final long DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION = Integer.MAX_VALUE;
    static final long DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE = 8192L; // from Netty default maxHeaderSize
    // Netty default is 200 for 30 seconds
    static final int DEFAULT_SERVER_HTTP2_MAX_RESET_FRAMES_PER_MINUTE = 400;
    static final String DEFAULT_BACKOFF_SPEC = "exponential=200:10000,jitter=0.2";
    static final int DEFAULT_MAX_TOTAL_ATTEMPTS = 10;
    static final long DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS = 0; // No delay.
    static final String ROUTE_CACHE_SPEC = "maximumSize=4096";
    static final String ROUTE_DECORATOR_CACHE_SPEC = "maximumSize=4096";
    static final String PARSED_PATH_CACHE_SPEC = "maximumSize=4096";
    static final String HEADER_VALUE_CACHE_SPEC = "maximumSize=4096";
    static final String CACHED_HEADERS = ":authority,:scheme,:method,accept-encoding,content-type";
    static final String FILE_SERVICE_CACHE_SPEC = "maximumSize=1024";
    static final String DNS_CACHE_SPEC = "maximumSize=4096";
    static final long DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS = 10000;
    static final long DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS = 3000;

    private DefaultFlagsProvider() {}

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public String name() {
        return "default";
    }

    @Override
    public Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return VERBOSE_EXCEPTION_SAMPLER;
    }

    @Override
    public Boolean verboseSocketExceptions() {
        return false;
    }

    @Override
    public Boolean verboseResponses() {
        return false;
    }

    @Override
    public RequestContextStorageProvider requestContextStorageProvider() {
        final List<RequestContextStorageProvider> providers = FlagsUtil.getRequestContextStorageProviders();
        if (providers.isEmpty()) {
            return new RequestContextStorageProvider() {
                @Override
                public RequestContextStorage newStorage() {
                    return RequestContextStorage.threadLocal();
                }

                @Override
                public String toString() {
                    return "thread-local";
                }
            };
        }
        return providers.get(0);
    }

    @Override
    public Boolean warnNettyVersions() {
        return true;
    }

    @Override
    public TransportType transportType() {
        if (TransportType.EPOLL.isAvailable()) {
            return TransportType.EPOLL;
        }
        if (TransportType.KQUEUE.isAvailable()) {
            return TransportType.KQUEUE;
        }
        return TransportType.NIO;
    }

    @Override
    public Boolean useOpenSsl() {
        return true;
    }

    @Override
    public TlsEngineType tlsEngineType() {
        return TlsEngineType.OPENSSL;
    }

    @Override
    public Boolean dumpOpenSslInfo() {
        return false;
    }

    @Override
    public Integer maxNumConnections() {
        return MAX_NUM_CONNECTIONS;
    }

    @Override
    public Integer numCommonWorkers(TransportType transportType) {
        final int numCpuCores = Runtime.getRuntime().availableProcessors();
        if (transportType == TransportType.IO_URING) {
            return numCpuCores;
        } else {
            return numCpuCores * 2;
        }
    }

    @Override
    public Integer numCommonBlockingTaskThreads() {
        return NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    @Override
    public Long defaultMaxRequestLength() {
        return DEFAULT_MAX_REQUEST_LENGTH;
    }

    @Override
    public Long defaultMaxResponseLength() {
        return DEFAULT_MAX_RESPONSE_LENGTH;
    }

    @Override
    public Long defaultRequestTimeoutMillis() {
        return DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public Long defaultResponseTimeoutMillis() {
        return DEFAULT_RESPONSE_TIMEOUT_MILLIS;
    }

    @Override
    public Long defaultConnectTimeoutMillis() {
        return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    @Override
    public Long defaultWriteTimeoutMillis() {
        return DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    @Override
    public Long defaultServerIdleTimeoutMillis() {
        return DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    @Override
    public Boolean defaultServerKeepAliveOnPing() {
        return DEFAULT_SERVER_KEEP_ALIVE_ON_PING;
    }

    @Override
    public Boolean defaultClientKeepAliveOnPing() {
        return DEFAULT_CLIENT_KEEP_ALIVE_ON_PING;
    }

    @Override
    public Long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    @Override
    public Integer defaultHttp1MaxInitialLineLength() {
        return DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH;
    }

    @Override
    public Integer defaultHttp1MaxHeaderSize() {
        return DEFAULT_HTTP1_MAX_HEADER_SIZE;
    }

    @Override
    public Integer defaultHttp1MaxChunkSize() {
        return DEFAULT_HTTP1_MAX_CHUNK_SIZE;
    }

    @Override
    public Boolean defaultUseHttp2Preface() {
        return true;
    }

    @Override
    public Boolean defaultPreferHttp1() {
        return false;
    }

    @Override
    public Boolean defaultUseHttp2WithoutAlpn() {
        return false;
    }

    @Override
    public Boolean defaultUseHttp1Pipelining() {
        return false;
    }

    @Override
    public Long defaultPingIntervalMillis() {
        return DEFAULT_PING_INTERVAL_MILLIS;
    }

    @Override
    public Integer defaultMaxServerNumRequestsPerConnection() {
        return DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION;
    }

    @Override
    public Integer defaultMaxClientNumRequestsPerConnection() {
        return DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION;
    }

    @Override
    public Long defaultMaxServerConnectionAgeMillis() {
        return DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS;
    }

    @Override
    public Long defaultMaxClientConnectionAgeMillis() {
        return DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS;
    }

    @Override
    public Long defaultServerConnectionDrainDurationMicros() {
        return DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS;
    }

    @Override
    public Long defaultClientHttp2GracefulShutdownTimeoutMillis() {
        return DEFAULT_CLIENT_HTTP2_GRACEFUL_SHUTDOWN_TIMEOUT_MILLIS;
    }

    @Override
    public Integer defaultHttp2InitialConnectionWindowSize() {
        return DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
    }

    @Override
    public Integer defaultHttp2InitialStreamWindowSize() {
        return DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE;
    }

    @Override
    public Integer defaultHttp2MaxFrameSize() {
        return DEFAULT_HTTP2_MAX_FRAME_SIZE;
    }

    @Override
    public Long defaultHttp2MaxStreamsPerConnection() {
        return DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION;
    }

    @Override
    public Long defaultHttp2MaxHeaderListSize() {
        return DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    @Override
    public Integer defaultServerHttp2MaxResetFramesPerMinute() {
        return DEFAULT_SERVER_HTTP2_MAX_RESET_FRAMES_PER_MINUTE;
    }

    @Override
    public String defaultBackoffSpec() {
        return DEFAULT_BACKOFF_SPEC;
    }

    @Override
    public Integer defaultMaxTotalAttempts() {
        return DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    @Override
    public Long defaultRequestAutoAbortDelayMillis() {
        return DEFAULT_REQUEST_AUTO_ABORT_DELAY_MILLIS;
    }

    @Override
    public String routeCacheSpec() {
        return ROUTE_CACHE_SPEC;
    }

    @Override
    public String routeDecoratorCacheSpec() {
        return ROUTE_DECORATOR_CACHE_SPEC;
    }

    @Override
    public String parsedPathCacheSpec() {
        return PARSED_PATH_CACHE_SPEC;
    }

    @Override
    public String headerValueCacheSpec() {
        return HEADER_VALUE_CACHE_SPEC;
    }

    @Override
    public List<String> cachedHeaders() {
        return Splitter.on(',').trimResults()
                       .omitEmptyStrings()
                       .splitToList(CACHED_HEADERS);
    }

    @Override
    public String fileServiceCacheSpec() {
        return FILE_SERVICE_CACHE_SPEC;
    }

    @Override
    public String dnsCacheSpec() {
        return DNS_CACHE_SPEC;
    }

    @Override
    public Predicate<InetAddress> preferredIpV4Addresses() {
        return new Predicate<InetAddress>() {
            @Override
            public boolean test(InetAddress ignored) {
                return true;
            }

            @Override
            public String toString() {
                return "*";
            }
        };
    }

    @Override
    public Boolean useJdkDnsResolver() {
        return false;
    }

    @Override
    public Boolean reportBlockedEventLoop() {
        return true;
    }

    @Override
    public Boolean reportMaskedRoutes() {
        return true;
    }

    @Override
    public Boolean validateHeaders() {
        return true;
    }

    @Override
    public Boolean tlsAllowUnsafeCiphers() {
        return false;
    }

    @Override
    public Integer defaultMaxClientHelloLength() {
        return DEFAULT_MAX_CLIENT_HELLO_LENGTH;
    }

    @Override
    public Set<TransientServiceOption> transientServiceOptions() {
        return ImmutableSet.of();
    }

    @Override
    public Boolean useDefaultSocketOptions() {
        return true;
    }

    @Override
    public Boolean useLegacyRouteDecoratorOrdering() {
        return false;
    }

    @Override
    public Boolean allowDoubleDotsInQueryString() {
        return false;
    }

    @Override
    public Boolean allowSemicolonInPathComponent() {
        return false;
    }

    @Override
    public Path defaultMultipartUploadsLocation() {
        return Paths.get(System.getProperty("java.io.tmpdir") +
                         File.separatorChar + "armeria" +
                         File.separatorChar + "multipart-uploads");
    }

    @Override
    public MultipartRemovalStrategy defaultMultipartRemovalStrategy() {
        return MultipartRemovalStrategy.ON_RESPONSE_COMPLETION;
    }

    @Override
    public Sampler<? super RequestContext> requestContextLeakDetectionSampler() {
        return Sampler.never();
    }

    @Override
    public MeterRegistry meterRegistry() {
        return Metrics.globalRegistry;
    }

    @Override
    public Long defaultUnhandledExceptionsReportIntervalMillis() {
        return DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS;
    }

    @Override
    public Long defaultUnloggedExceptionsReportIntervalMillis() {
        return DEFAULT_UNLOGGED_EXCEPTIONS_REPORT_INTERVAL_MILLIS;
    }

    @Override
    public DistributionStatisticConfig distributionStatisticConfig() {
        return DistributionStatisticConfigUtil.DEFAULT_DIST_STAT_CFG;
    }

    @Override
    public Long defaultHttp1ConnectionCloseDelayMillis() {
        return DEFAULT_HTTP1_CONNECTION_CLOSE_DELAY_MILLIS;
    }
}
