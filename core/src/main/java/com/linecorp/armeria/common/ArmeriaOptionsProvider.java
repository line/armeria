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
 * A Java SPI (Service Provider Interface) for the {@link Flags} value.
 * Value provides from Java SPI will be ignored if there corresponding valid JVM option.
 */
@UnstableApi
public interface ArmeriaOptionsProvider {

    /**
     * Return verboseExceptionSampler. See {@link Flags#verboseExceptionSampler()}.
     */
    default Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return DefaultFlags.VERBOSE_EXCEPTION_SAMPLER;
    }

    /**
     * Return verboseExceptionSamplerSpec. See {@link Flags#verboseExceptionSamplerSpec()}.
     */
    default String verboseExceptionSamplerSpec() {
        return DefaultFlags.VERBOSE_EXCEPTION_SAMPLER_SPEC;
    }

    /**
     * Return verboseSocketExceptions. See {@link Flags#verboseSocketExceptions()}.
     */
    default boolean verboseSocketExceptions() {
        return DefaultFlags.VERBOSE_SOCKET_EXCEPTIONS;
    }

    /**
     * Return verboseResponses. See {@link Flags#verboseResponses()}.
     */
    default boolean verboseResponses() {
        return DefaultFlags.VERBOSE_RESPONSES;
    }

    /**
     * Return requestContextStorageProvider. See {@link Flags#requestContextStorageProvider()}.
     */
    @Nullable
    default String requestContextStorageProvider() {
        return DefaultFlags.REQUEST_CONTEXT_STORAGE_PROVIDER;
    }

    /**
     * Return warnNettyVersions. See {@link Flags#warnNettyVersions()}.
     */
    default boolean warnNettyVersions() {
        return DefaultFlags.WARN_NETTY_VERSIONS;
    }

    /**
     * Return transportType. See {@link Flags#transportType()}.
     */
    default TransportType transportType() {
        return DefaultFlags.TRANSPORT_TYPE;
    }

    /**
     * Return useOpenSsl. See {@link Flags#useOpenSsl()}.
     */
    default boolean useOpenSsl() {
        return DefaultFlags.useOpenSsl;
    }

    /**
     * Return dumpOpenSslInfo. See {@link Flags#dumpOpenSslInfo()}.
     */
    default boolean dumpOpenSslInfo() {
        return DefaultFlags.dumpOpenSslInfo;
    }

    /**
     * Return maxNumConnections. See {@link Flags#maxNumConnections()}.
     */
    default int maxNumConnections() {
        return DefaultFlags.MAX_NUM_CONNECTIONS;
    }

    /**
     * Return numCommonWorkers. See {@link Flags#numCommonWorkers()}.
     */
    default int numCommonWorkers() {
        return DefaultFlags.NUM_COMMON_WORKERS;
    }

    /**
     * Return numCommonBlockingTaskThreads. See {@link Flags#numCommonBlockingTaskThreads()}.
     */
    default int numCommonBlockingTaskThreads() {
        return DefaultFlags.NUM_COMMON_BLOCKING_TASK_THREADS;
    }

    /**
     * Return defaultMaxRequestLength. See {@link Flags#defaultMaxRequestLength()}.
     */
    default long defaultMaxRequestLength() {
        return DefaultFlags.DEFAULT_MAX_REQUEST_LENGTH;
    }

    /**
     * Return defaultMaxResponseLength. See {@link Flags#defaultMaxResponseLength()}.
     */
    default long defaultMaxResponseLength() {
        return DefaultFlags.DEFAULT_MAX_RESPONSE_LENGTH;
    }

    /**
     * Return defaultRequestTimeoutMillis. See {@link Flags#defaultRequestTimeoutMillis()}.
     */
    default long defaultRequestTimeoutMillis() {
        return DefaultFlags.DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultResponseTimeoutMillis. See {@link Flags#defaultResponseTimeoutMillis()}.
     */
    default long defaultResponseTimeoutMillis() {
        return DefaultFlags.DEFAULT_RESPONSE_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultConnectTimeoutMillis. See {@link Flags#defaultConnectTimeoutMillis()}.
     */
    default long defaultConnectTimeoutMillis() {
        return DefaultFlags.DEFAULT_CONNECT_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultWriteTimeoutMillis. See {@link Flags#defaultWriteTimeoutMillis()}.
     */
    default long defaultWriteTimeoutMillis() {
        return DefaultFlags.DEFAULT_WRITE_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultServerIdleTimeoutMillis. See {@link Flags#defaultServerIdleTimeoutMillis()}.
     */
    default long defaultServerIdleTimeoutMillis() {
        return DefaultFlags.DEFAULT_SERVER_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultClientIdleTimeoutMillis. See {@link Flags#defaultClientIdleTimeoutMillis()}.
     */
    default long defaultClientIdleTimeoutMillis() {
        return DefaultFlags.DEFAULT_CLIENT_IDLE_TIMEOUT_MILLIS;
    }

    /**
     * Return defaultHttp1MaxInitialLineLength. See {@link Flags#defaultHttp1MaxInitialLineLength()}.
     */
    default int defaultHttp1MaxInitialLineLength() {
        return DefaultFlags.DEFAULT_MAX_HTTP1_INITIAL_LINE_LENGTH;
    }

    /**
     * Return defaultHttp1MaxHeaderSize. See {@link Flags#defaultHttp1MaxHeaderSize()}.
     */
    default int defaultHttp1MaxHeaderSize() {
        return DefaultFlags.DEFAULT_MAX_HTTP1_HEADER_SIZE;
    }

    /**
     * Return defaultHttp1MaxChunkSize. See {@link Flags#defaultHttp1MaxChunkSize()}.
     */
    default int defaultHttp1MaxChunkSize() {
        return DefaultFlags.DEFAULT_HTTP1_MAX_CHUNK_SIZE;
    }

    /**
     * Return defaultUseHttp2Preface. See {@link Flags#defaultUseHttp2Preface()}.
     */
    default boolean defaultUseHttp2Preface() {
        return DefaultFlags.DEFAULT_USE_HTTP2_PREFACE;
    }

    /**
     * Return defaultUseHttp1Pipelining. See {@link Flags#defaultUseHttp1Pipelining()}.
     */
    default boolean defaultUseHttp1Pipelining() {
        return DefaultFlags.DEFAULT_USE_HTTP1_PIPELINING;
    }

    /**
     * Return defaultPingIntervalMillis. See {@link Flags#defaultPingIntervalMillis()}.
     */
    default long defaultPingIntervalMillis() {
        return DefaultFlags.DEFAULT_PING_INTERVAL_MILLIS;
    }

    /**
     * Return defaultMaxServerNumRequestsPerConnection.
     * See {@link Flags#defaultMaxServerNumRequestsPerConnection()}.
     */
    default int defaultMaxServerNumRequestsPerConnection() {
        return DefaultFlags.DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION;
    }

    /**
     * Return defaultMaxClientNumRequestsPerConnection.
     * See {@link Flags#defaultMaxClientNumRequestsPerConnection()}.
     */
    default int defaultMaxClientNumRequestsPerConnection() {
        return DefaultFlags.DEFAULT_MAX_NUM_REQUESTS_PER_CONNECTION;
    }

    /**
     * Return defaultMaxServerConnectionAgeMillis. See {@link Flags#defaultMaxServerConnectionAgeMillis()}.
     */
    default long defaultMaxServerConnectionAgeMillis() {
        return DefaultFlags.DEFAULT_MAX_CONNECTION_AGE_MILLIS;
    }

    /**
     * Return defaultMaxClientConnectionAgeMillis. See {@link Flags#defaultMaxClientConnectionAgeMillis()}.
     */
    default long defaultMaxClientConnectionAgeMillis() {
        return DefaultFlags.DEFAULT_MAX_CONNECTION_AGE_MILLIS;
    }

    /**
     * Return defaultServerConnectionDrainDurationMicros.
     * See {@link Flags#defaultServerConnectionDrainDurationMicros()}.
     */
    default long defaultServerConnectionDrainDurationMicros() {
        return DefaultFlags.DEFAULT_SERVER_CONNECTION_DRAIN_DURATION_MICROS;
    }

    /**
     * Return defaultHttp2InitialConnectionWindowSize.
     * See {@link Flags#defaultHttp2InitialConnectionWindowSize()}.
     */
    default int defaultHttp2InitialConnectionWindowSize() {
        return DefaultFlags.DEFAULT_HTTP2_INITIAL_CONNECTION_WINDOW_SIZE;
    }

    /**
     * Return defaultHttp2InitialStreamWindowSize. See {@link Flags#defaultHttp2InitialStreamWindowSize()}.
     */
    default int defaultHttp2InitialStreamWindowSize() {
        return DefaultFlags.DEFAULT_HTTP2_INITIAL_STREAM_WINDOW_SIZE;
    }

    /**
     * Return defaultHttp2MaxFrameSize. See {@link Flags#defaultHttp2MaxFrameSize()}.
     */
    default int defaultHttp2MaxFrameSize() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_FRAME_SIZE;
    }

    /**
     * Return defaultHttp2MaxStreamsPerConnection. See {@link Flags#defaultHttp2MaxStreamsPerConnection()}.
     */
    default long defaultHttp2MaxStreamsPerConnection() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_STREAMS_PER_CONNECTION;
    }

    /**
     * Return defaultHttp2MaxHeaderListSize. See {@link Flags#defaultHttp2MaxHeaderListSize()}.
     */
    default long defaultHttp2MaxHeaderListSize() {
        return DefaultFlags.DEFAULT_HTTP2_MAX_HEADER_LIST_SIZE;
    }

    /**
     * Return defaultBackoffSpec. See {@link Flags#defaultBackoffSpec()}.
     */
    default String defaultBackoffSpec() {
        return DefaultFlags.DEFAULT_BACKOFF_SPEC;
    }

    /**
     * Return defaultMaxTotalAttempts. See {@link Flags#defaultMaxTotalAttempts()}.
     */
    default int defaultMaxTotalAttempts() {
        return DefaultFlags.DEFAULT_MAX_TOTAL_ATTEMPTS;
    }

    /**
     * Return routeCacheSpec. See {@link Flags#routeCacheSpec()}.
     */
    @Nullable
    default String routeCacheSpec() {
        return DefaultFlags.ROUTE_CACHE_SPEC;
    }

    /**
     * Return routeDecoratorCacheSpec. See {@link Flags#routeDecoratorCacheSpec()}.
     */
    @Nullable
    default String routeDecoratorCacheSpec() {
        return DefaultFlags.ROUTE_DECORATOR_CACHE_SPEC;
    }

    /**
     * Return parsedPathCacheSpec. See {@link Flags#parsedPathCacheSpec()}.
     */
    @Nullable
    default String parsedPathCacheSpec() {
        return DefaultFlags.PARSED_PATH_CACHE_SPEC;
    }

    /**
     * Return headerValueCacheSpec. See {@link Flags#headerValueCacheSpec()}.
     */
    @Nullable
    default String headerValueCacheSpec() {
        return DefaultFlags.HEADER_VALUE_CACHE_SPEC;
    }

    /**
     * Return cachedHeaders. See {@link Flags#cachedHeaders()}.
     */
    default List<String> cachedHeaders() {
        return DefaultFlags.CACHED_HEADERS;
    }

    /**
     * Return fileServiceCacheSpec. See {@link Flags#fileServiceCacheSpec()}.
     */
    @Nullable
    default String fileServiceCacheSpec() {
        return DefaultFlags.FILE_SERVICE_CACHE_SPEC;
    }

    /**
     * Return dnsCacheSpec. See {@link Flags#dnsCacheSpec()}.
     */
    default String dnsCacheSpec() {
        return DefaultFlags.DNS_CACHE_SPEC;
    }

    /**
     * Return preferredIpV4Addresses. See {@link Flags#preferredIpV4Addresses()}.
     */
    @Nullable
    default Predicate<InetAddress> preferredIpV4Addresses() {
        return DefaultFlags.PREFERRED_IP_V4_ADDRESSES;
    }

    /**
     * Return useJdkDnsResolver. See {@link Flags#useJdkDnsResolver()}.
     */
    default boolean useJdkDnsResolver() {
        return DefaultFlags.USE_JDK_DNS_RESOLVER;
    }

    /**
     * Return reportBlockedEventLoop. See {@link Flags#reportBlockedEventLoop()}.
     */
    default boolean reportBlockedEventLoop() {
        return DefaultFlags.REPORT_BLOCKED_EVENT_LOOP;
    }

    /**
     * Return validateHeaders. See {@link Flags#validateHeaders()}.
     */
    default boolean validateHeaders() {
        return DefaultFlags.VALIDATE_HEADERS;
    }

    /**
     * Return tlsAllowUnsafeCiphers. See {@link Flags#tlsAllowUnsafeCiphers()}.
     */
    default boolean tlsAllowUnsafeCiphers() {
        return DefaultFlags.DEFAULT_TLS_ALLOW_UNSAFE_CIPHERS;
    }

    /**
     * Return transientServiceOptions. See {@link Flags#transientServiceOptions()}.
     */
    default Set<TransientServiceOption> transientServiceOptions() {
        return DefaultFlags.TRANSIENT_SERVICE_OPTIONS;
    }

    /**
     * Return useDefaultSocketOptions. See {@link Flags#useDefaultSocketOptions()}.
     */
    default boolean useDefaultSocketOptions() {
        return DefaultFlags.USE_DEFAULT_SOCKET_OPTIONS;
    }

    /**
     * Return useLegacyRouteDecoratorOrdering. See {@link Flags#useLegacyRouteDecoratorOrdering()}.
     */
    default boolean useLegacyRouteDecoratorOrdering() {
        return DefaultFlags.DEFAULT_USE_LEGACY_ROUTE_DECORATOR_ORDERING;
    }

    /**
     * Return allowDoubleDotsInQueryString. See {@link Flags#allowDoubleDotsInQueryString()}.
     */
    default boolean allowDoubleDotsInQueryString() {
        return DefaultFlags.ALLOW_DOUBLE_DOTS_IN_QUERY_STRING;
    }
}
