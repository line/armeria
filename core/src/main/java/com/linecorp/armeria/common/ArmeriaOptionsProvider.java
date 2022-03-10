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

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * A Java SPI (Service Provider Interface) for the {@link Flags} value
 */
@UnstableApi
public interface ArmeriaOptionsProvider {

    default Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return DefaultFlags.VERBOSE_EXCEPTION_SAMPLER;
    }

    default String verboseExceptionSamplerSpec() {
        return DefaultFlags.VERBOSE_EXCEPTION_SAMPLER_SPEC;
    }

    default boolean verboseSocketExceptions() {
        return DefaultFlags.VERBOSE_SOCKET_EXCEPTIONS;
    }

    default boolean verboseResponses() {
        return DefaultFlags.VERBOSE_RESPONSES;
    }

    @Nullable
    default String requestContextStorageProvider() {
        return DefaultFlags.REQUEST_CONTEXT_STORAGE_PROVIDER;
    }

    default boolean warnNettyVersions() {
        return DefaultFlags.WARN_NETTY_VERSIONS;
    }

    default TransportType transportType() {
        return DefaultFlags.TRANSPORT_TYPE;
    }

    default boolean useOpenSsl() {
        return DefaultFlags.useOpenSsl;
    }

    default boolean dumpOpenSslInfo() {
        return DefaultFlags.dumpOpenSslInfo;
    }

    default int maxNumConnections() {
        return DefaultFlags.MAX_NUM_CONNECTIONS;
    }

    default int numCommonWorkers() {
        return DefaultFlags.NUM_COMMON_WORKERS;
    }

    default int numCommonBlockingTaskThreads() {
        return DefaultFlags.NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    default long defaultMaxRequestLength() {
        return DefaultFlags.DEFAULT_MAX_REQUEST_LENGTH;
    }

    default long defaultMaxResponseLength() {
        return DefaultFlags.DEFAULT_MAX_RESPONSE_LENGTH;
    }

    default long defaultRequestTimeoutMillis() {
        return DefaultFlags.DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    default long defaultResponseTimeoutMillis() {
        return DefaultFlags.DEFAULT_RESPONSE_TIMEOUT_MILLIS;
    }

    default long defaultConnectTimeoutMillis() {
        return DefaultFlags.DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    default long defaultWriteTimeoutMillis() {
        return DefaultFlags.DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    default long defaultServerIdleTimeoutMillis() {
        return DefaultFlags.DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    default long defaultClientIdleTimeoutMillis() {
        return DefaultFlags.DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    default int defaultHttp1MaxInitialLineLength() {
        return DefaultFlags.DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH;
    }

    default int defaultHttp1MaxHeaderSize() {
        return DefaultFlags.DEFAULT_MAX_HTTP1_HEADER_SIZE;
    }

    default int defaultHttp1MaxChunkSize() {
        return DefaultFlags.DEFAULT_HTTP1_MAX_CHUNK_SIZE;
    }

    default boolean defaultUseHttp2Preface() {
        return DefaultFlags.DEFAULT_USE_HTTP2_PREFACE;
    }

    default boolean defaultUseHttp1Pipelining() {
        return DefaultFlags.DEFAULT_USE_HTTP1_PIPELINING;
    }

    default long defaultPingIntervalMillis() {
        return DefaultFlags.DEFAULT_PING_INTERVAL_MILLIS;
    }

    default int defaultMaxServerNumRequestsPerConnection() {
        return DefaultFlags.DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION;
    }

    default int defaultMaxClientNumRequestsPerConnection() {
        return DefaultFlags.DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION;
    }

    default long defaultMaxServerConnectionAgeMillis() {
        return DefaultFlags.DEFAULT_MAX_CONNECTION_AGE_MILLIS;
    }

    default long defaultMaxClientConnectionAgeMillis() {
        return DefaultFlags.DEFAULT_MAX_CONNECTION_AGE_MILLIS;
    }

    default long defaultServerConnectionDrainDurationMicros() {
        return DefaultFlags.DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS;
    }

    default int defaultHttp2InitialConnectionWindowSize() {
        return DefaultFlags.DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
    }

    default int defaultHttp2InitialStreamWindowSize() {
        return DefaultFlags.DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE;
    }

    default int defaultHttp2MaxFrameSize() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_FRAME_SIZE;
    }

    default long defaultHttp2MaxStreamsPerConnection() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION;
    }

    default long defaultHttp2MaxHeaderListSize() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    default String defaultBackoffSpec() {
        return DefaultFlags.DEFAULT_BACKOFF_SPEC;
    }

    default int defaultMaxTotalAttempts() {
        return DefaultFlags.DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    @Nullable
    default String routeCacheSpec() {
        return DefaultFlags.ROUTE_CACHE_SPEC;
    }

    @Nullable
    default String routeDecoratorCacheSpec() {
        return DefaultFlags.ROUTE_DECORATOR_CACHE_SPEC;
    }

    @Nullable
    default String parsedPathCacheSpec() {
        return DefaultFlags.PARSED_PATH_CACHE_SPEC;
    }

    @Nullable
    default String headerValueCacheSpec() {
        return DefaultFlags.HEADER_VALUE_CACHE_SPEC;
    }

    default List<String> cachedHeaders() {
        return DefaultFlags.CACHED_HEADERS;
    }

    @Nullable
    default String fileServiceCacheSpec() {
        return DefaultFlags.FILE_SERVICE_CACHE_SPEC;
    }

    default String dnsCacheSpec() {
        return DefaultFlags.DNS_CACHE_SPEC;
    }

    @Nullable
    default Predicate<InetAddress> preferredIpV4Addresses() {
        return DefaultFlags.PREFERRED_IP_V4_ADDRESSES;
    }

    default boolean useJdkDnsResolver() {
        return DefaultFlags.USE_JDK_DNS_RESOLVER;
    }

    default boolean reportBlockedEventLoop() {
        return DefaultFlags.REPORT_BLOCKED_EVENT_LOOP;
    }

    default boolean validateHeaders() {
        return DefaultFlags.VALIDATE_HEADERS;
    }

    default boolean tlsAllowUnsafeCiphers() {
        return DefaultFlags.DEFAULT_TLS_ALLOW_UNSAFE_CIPHERS;
    }

    default Set<TransientServiceOption> transientServiceOptions() {
        return DefaultFlags.TRANSIENT_SERVICE_OPTIONS;
    }

    default boolean useDefaultSocketOptions() {
        return DefaultFlags.USE_DEFAULT_SOCKET_OPTIONS;
    }

    default boolean useLegacyRouteDecoratorOrdering() {
        return DefaultFlags.DEFAULT_USE_LEGACY_ROUTE_DECORATOR_ORDERING;
    }

    default boolean allowDoubleDotsInQueryString() {
        return DefaultFlags.ALLOW_DOUBLE_DOTS_IN_QUERY_STRING;
    }
}
