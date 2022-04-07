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
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Implementation of {@link FlagsProvider} which provides default values to {@link Flags}.
 */
final class DefaultFlagsProvider implements FlagsProvider {

    static final DefaultFlagsProvider INSTANCE = new DefaultFlagsProvider();

    private DefaultFlagsProvider() {}

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    static final String VERBOSE_EXCEPTION_SAMPLER_SPEC = "rate-limit=10";

    @Override
    public Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return new ExceptionSampler(VERBOSE_EXCEPTION_SAMPLER_SPEC);
    }

    @Override
    public String verboseExceptionSamplerSpec() {
        return VERBOSE_EXCEPTION_SAMPLER_SPEC;
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
    public String requestContextStorageProvider() {
        return null;
    }

    @Override
    public Boolean warnNettyVersions() {
        return true;
    }

    @Override
    public TransportType transportType() {
        return TransportType.EPOLL.isAvailable() ? TransportType.EPOLL : TransportType.NIO;
    }

    @Override
    public Boolean useOpenSsl() {
        return true;
    }

    @Override
    public Boolean dumpOpenSslInfo() {
        return false;
    }

    static final int MAX_NUM_CONNECTIONS = 1;

    @Override
    public Integer maxNumConnections() {
        return MAX_NUM_CONNECTIONS;
    }

    @Override
    public Integer numCommonWorkers() {
        final int defaultNumCpuCores = Runtime.getRuntime().availableProcessors();
        return defaultNumCpuCores * 2;
    }

    static final int NUM_COMMON_BLOCKING_TASK_THREADS = 200; // from Tomcat default maxThreads

    @Override
    public Integer numCommonBlockingTaskThreads() {
        return NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    static final long DEFAULT_MAX_REQUEST_LENGTH = 10 * 1024 * 1024; // 10 MiB

    @Override
    public Long defaultMaxRequestLength() {
        return DEFAULT_MAX_REQUEST_LENGTH;
    }

    static final long DEFAULT_MAX_RESPONSE_LENGTH = 10 * 1024 * 1024; // 10 MiB

    @Override
    public Long defaultMaxResponseLength() {
        return DEFAULT_MAX_RESPONSE_LENGTH;
    }

    static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 10 * 1000; // 10 seconds

    @Override
    public Long defaultRequestTimeoutMillis() {
        return DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = 15 * 1000; // 15 seconds

    @Override
    public Long defaultResponseTimeoutMillis() {
        return DEFAULT_RESPONSE_TIMEOUT_MILLIS;
    }

    static final long DEFAULT_CONNECT_TIMEOUT_MILLIS = 3200; // 3.2 seconds

    @Override
    public Long defaultConnectTimeoutMillis() {
        return DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    static final long DEFAULT_WRITE_TIMEOUT_MILLIS = 1000; // 1 second

    @Override
    public Long defaultWriteTimeoutMillis() {
        return DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    static final long DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS = 15000; // 15 seconds

    @Override
    public Long defaultServerIdleTimeoutMillis() {
        return DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    static final long DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS = 10000; // 10 seconds

    @Override
    public Long defaultClientIdleTimeoutMillis() {
        return DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    static final int DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH = 4096; // from Netty

    @Override
    public Integer defaultHttp1MaxInitialLineLength() {
        return DEFAULT_HTTP1_MAX_INITIAL_LINE_LENGTH;
    }

    static final int DEFAULT_HTTP1_MAX_HEADER_SIZE = 8192; // from Netty

    @Override
    public Integer defaultHttp1MaxHeaderSize() {
        return 8192; // from Netty
    }

    static final int DEFAULT_HTTP1_MAX_CHUNK_SIZE = 8192; // from Netty

    @Override
    public Integer defaultHttp1MaxChunkSize() {
        return DEFAULT_HTTP1_MAX_CHUNK_SIZE;
    }

    @Override
    public Boolean defaultUseHttp2Preface() {
        return true;
    }

    @Override
    public Boolean defaultUseHttp1Pipelining() {
        return false;
    }

    static final long DEFAULT_PING_INTERVAL_MILLIS = 0; // Disabled

    @Override
    public Long defaultPingIntervalMillis() {
        return DEFAULT_PING_INTERVAL_MILLIS;
    }

    static final int DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled

    @Override
    public Integer defaultMaxServerNumRequestsPerConnection() {
        return DEFAULT_MAX_SERVER_NUM_REQUESTS_PER_CONNECTION;
    }

    static final int DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION = 0; // Disabled

    @Override
    public Integer defaultMaxClientNumRequestsPerConnection() {
        return DEFAULT_MAX_CLIENT_NUM_REQUESTS_PER_CONNECTION;
    }

    static final long DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS = 0; // Disabled

    @Override
    public Long defaultMaxServerConnectionAgeMillis() {
        return DEFAULT_MAX_SERVER_CONNECTION_AGE_MILLIS;
    }

    static final long DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS = 0; // Disabled

    @Override
    public Long defaultMaxClientConnectionAgeMillis() {
        return DEFAULT_MAX_CLIENT_CONNECTION_AGE_MILLIS;
    }

    static final long DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS = 1000000;

    @Override
    public Long defaultServerConnectionDrainDurationMicros() {
        return DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS;
    }

    static final int DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE = 1024 * 1024; // 1MiB

    @Override
    public Integer defaultHttp2InitialConnectionWindowSize() {
        return DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
    }

    static final int DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE = 1024 * 1024; // 1MiB

    @Override
    public Integer defaultHttp2InitialStreamWindowSize() {
        return DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE;
    }

    static final int DEFAULT_HTTP2_MAX_FRAME_SIZE = 16384; // From HTTP/2 specification

    @Override
    public Integer defaultHttp2MaxFrameSize() {
        return DEFAULT_HTTP2_MAX_FRAME_SIZE;
    }

    // Can't use 0xFFFFFFFFL because some implementations use a signed 32-bit integer to store HTTP/2 SETTINGS
    // parameter values, thus anything greater than 0x7FFFFFFF will break them or make them unhappy.
    static final long DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION = Integer.MAX_VALUE;

    @Override
    public Long defaultHttp2MaxStreamsPerConnection() {
        return DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION;
    }

    static final long DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE = 8192L; // from Netty default maxHeaderSize

    @Override
    public Long defaultHttp2MaxHeaderListSize() {
        return DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    static final String DEFAULT_BACKOFF_SPEC = "exponential=200:10000,jitter=0.2";

    @Override
    public String defaultBackoffSpec() {
        return DEFAULT_BACKOFF_SPEC;
    }

    static final int DEFAULT_MAX_TOTAL_ATTEMPTS = 10;

    @Override
    public Integer defaultMaxTotalAttempts() {
        return DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    static final String ROUTE_CACHE_SPEC = "maximumSize=4096";

    @Override
    public String routeCacheSpec() {
        return ROUTE_CACHE_SPEC;
    }

    static final String ROUTE_DECORATOR_CACHE_SPEC = "maximumSize=4096";

    @Override
    public String routeDecoratorCacheSpec() {
        return ROUTE_DECORATOR_CACHE_SPEC;
    }

    static final String PARSED_PATH_CACHE_SPEC = "maximumSize=4096";

    @Override
    public String parsedPathCacheSpec() {
        return PARSED_PATH_CACHE_SPEC;
    }

    static final String HEADER_VALUE_CACHE_SPEC = "maximumSize=4096";

    @Override
    public String headerValueCacheSpec() {
        return HEADER_VALUE_CACHE_SPEC;
    }

    static final String CACHED_HEADERS = ":authority,:scheme,:method,accept-encoding,content-type";

    @Override
    public List<String> cachedHeaders() {
        return Splitter.on(',').trimResults()
                       .omitEmptyStrings()
                       .splitToList(CACHED_HEADERS);
    }

    static final String FILE_SERVICE_CACHE_SPEC = "maximumSize=1024";

    @Override
    public String fileServiceCacheSpec() {
        return FILE_SERVICE_CACHE_SPEC;
    }

    static final String DNS_CACHE_SPEC = "maximumSize=4096";

    @Override
    public String dnsCacheSpec() {
        return DNS_CACHE_SPEC;
    }

    @Override
    public Predicate<InetAddress> preferredIpV4Addresses() {
        return null;
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
    public Boolean validateHeaders() {
        return true;
    }

    @Override
    public Boolean tlsAllowUnsafeCiphers() {
        return false;
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
    public Path defaultMultipartUploadsLocation() {
        return Paths.get(System.getProperty("java.io.tmpdir") +
                         File.separatorChar + "armeria" +
                         File.separatorChar + "multipart-uploads");
    }
}
