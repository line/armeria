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
public interface FlagsProvider {

    /**
     * Return verboseExceptionSampler. See {@link Flags#verboseExceptionSampler()}.
     */
    @Nullable
    default Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return null;
    }

    /**
     * Return verboseExceptionSamplerSpec. See {@link Flags#verboseExceptionSamplerSpec()}.
     */
    @Nullable
    default String verboseExceptionSamplerSpec() {
        return null;
    }

    /**
     * Return verboseSocketExceptions. See {@link Flags#verboseSocketExceptions()}.
     */
    @Nullable
    default Boolean verboseSocketExceptions() {
        return null;
    }

    /**
     * Return verboseResponses. See {@link Flags#verboseResponses()}.
     */
    @Nullable
    default Boolean verboseResponses() {
        return null;
    }

    /**
     * Return requestContextStorageProvider. See {@link Flags#requestContextStorageProvider()}.
     */
    @Nullable
    default String requestContextStorageProvider() {
        return null;
    }

    /**
     * Return warnNettyVersions. See {@link Flags#warnNettyVersions()}.
     */
    @Nullable
    default Boolean warnNettyVersions() {
        return null;
    }

    /**
     * Return transportType. See {@link Flags#transportType()}.
     */
    @Nullable
    default TransportType transportType() {
        return null;
    }

    /**
     * Return useOpenSsl. See {@link Flags#useOpenSsl()}.
     */
    @Nullable
    default Boolean useOpenSsl() {
        return null;
    }

    /**
     * Return dumpOpenSslInfo. See {@link Flags#dumpOpenSslInfo()}.
     */
    @Nullable
    default Boolean dumpOpenSslInfo() {
        return null;
    }

    /**
     * Return maxNumConnections. See {@link Flags#maxNumConnections()}.
     */
    @Nullable
    default Integer maxNumConnections() {
        return null;
    }

    /**
     * Return numCommonWorkers. See {@link Flags#numCommonWorkers()}.
     */
    @Nullable
    default Integer numCommonWorkers() {
        return null;
    }

    /**
     * Return numCommonBlockingTaskThreads. See {@link Flags#numCommonBlockingTaskThreads()}.
     */
    @Nullable
    default Integer numCommonBlockingTaskThreads() {
        return null;
    }

    /**
     * Return defaultMaxRequestLength. See {@link Flags#defaultMaxRequestLength()}.
     */
    @Nullable
    default Long defaultMaxRequestLength() {
        return null;
    }

    /**
     * Return defaultMaxResponseLength. See {@link Flags#defaultMaxResponseLength()}.
     */
    @Nullable
    default Long defaultMaxResponseLength() {
        return null;
    }

    /**
     * Return defaultRequestTimeoutMillis. See {@link Flags#defaultRequestTimeoutMillis()}.
     */
    @Nullable
    default Long defaultRequestTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultResponseTimeoutMillis. See {@link Flags#defaultResponseTimeoutMillis()}.
     */
    @Nullable
    default Long defaultResponseTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultConnectTimeoutMillis. See {@link Flags#defaultConnectTimeoutMillis()}.
     */
    @Nullable
    default Long defaultConnectTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultWriteTimeoutMillis. See {@link Flags#defaultWriteTimeoutMillis()}.
     */
    @Nullable
    default Long defaultWriteTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultServerIdleTimeoutMillis. See {@link Flags#defaultServerIdleTimeoutMillis()}.
     */
    @Nullable
    default Long defaultServerIdleTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultClientIdleTimeoutMillis. See {@link Flags#defaultClientIdleTimeoutMillis()}.
     */
    @Nullable
    default Long defaultClientIdleTimeoutMillis() {
        return null;
    }

    /**
     * Return defaultHttp1MaxInitialLineLength. See {@link Flags#defaultHttp1MaxInitialLineLength()}.
     */
    @Nullable
    default Integer defaultHttp1MaxInitialLineLength() {
        return null;
    }

    /**
     * Return defaultHttp1MaxHeaderSize. See {@link Flags#defaultHttp1MaxHeaderSize()}.
     */
    @Nullable
    default Integer defaultHttp1MaxHeaderSize() {
        return null;
    }

    /**
     * Return defaultHttp1MaxChunkSize. See {@link Flags#defaultHttp1MaxChunkSize()}.
     */
    @Nullable
    default Integer defaultHttp1MaxChunkSize() {
        return null;
    }

    /**
     * Return defaultUseHttp2Preface. See {@link Flags#defaultUseHttp2Preface()}.
     */
    @Nullable
    default Boolean defaultUseHttp2Preface() {
        return null;
    }

    /**
     * Return defaultUseHttp1Pipelining. See {@link Flags#defaultUseHttp1Pipelining()}.
     */
    @Nullable
    default Boolean defaultUseHttp1Pipelining() {
        return null;
    }

    /**
     * Return defaultPingIntervalMillis. See {@link Flags#defaultPingIntervalMillis()}.
     */
    @Nullable
    default Long defaultPingIntervalMillis() {
        return null;
    }

    /**
     * Return defaultMaxServerNumRequestsPerConnection.
     * See {@link Flags#defaultMaxServerNumRequestsPerConnection()}.
     */
    @Nullable
    default Integer defaultMaxServerNumRequestsPerConnection() {
        return null;
    }

    /**
     * Return defaultMaxClientNumRequestsPerConnection.
     * See {@link Flags#defaultMaxClientNumRequestsPerConnection()}.
     */
    @Nullable
    default Integer defaultMaxClientNumRequestsPerConnection() {
        return null;
    }

    /**
     * Return defaultMaxServerConnectionAgeMillis. See {@link Flags#defaultMaxServerConnectionAgeMillis()}.
     */
    @Nullable
    default Long defaultMaxServerConnectionAgeMillis() {
        return null;
    }

    /**
     * Return defaultMaxClientConnectionAgeMillis. See {@link Flags#defaultMaxClientConnectionAgeMillis()}.
     */
    @Nullable
    default Long defaultMaxClientConnectionAgeMillis() {
        return null;
    }

    /**
     * Return defaultServerConnectionDrainDurationMicros.
     * See {@link Flags#defaultServerConnectionDrainDurationMicros()}.
     */
    @Nullable
    default Long defaultServerConnectionDrainDurationMicros() {
        return null;
    }

    /**
     * Return defaultHttp2InitialConnectionWindowSize.
     * See {@link Flags#defaultHttp2InitialConnectionWindowSize()}.
     */
    @Nullable
    default Integer defaultHttp2InitialConnectionWindowSize() {
        return null;
    }

    /**
     * Return defaultHttp2InitialStreamWindowSize. See {@link Flags#defaultHttp2InitialStreamWindowSize()}.
     */
    @Nullable
    default Integer defaultHttp2InitialStreamWindowSize() {
        return null;
    }

    /**
     * Return defaultHttp2MaxFrameSize. See {@link Flags#defaultHttp2MaxFrameSize()}.
     */
    @Nullable
    default Integer defaultHttp2MaxFrameSize() {
        return null;
    }

    /**
     * Return defaultHttp2MaxStreamsPerConnection. See {@link Flags#defaultHttp2MaxStreamsPerConnection()}.
     */
    @Nullable
    default Long defaultHttp2MaxStreamsPerConnection() {
        return null;
    }

    /**
     * Return defaultHttp2MaxHeaderListSize. See {@link Flags#defaultHttp2MaxHeaderListSize()}.
     */
    @Nullable
    default Long defaultHttp2MaxHeaderListSize() {
        return null;
    }

    /**
     * Return defaultBackoffSpec. See {@link Flags#defaultBackoffSpec()}.
     */
    @Nullable
    default String defaultBackoffSpec() {
        return null;
    }

    /**
     * Return defaultMaxTotalAttempts. See {@link Flags#defaultMaxTotalAttempts()}.
     */
    @Nullable
    default Integer defaultMaxTotalAttempts() {
        return null;
    }

    /**
     * Return routeCacheSpec. See {@link Flags#routeCacheSpec()}.
     */
    @Nullable
    default String routeCacheSpec() {
        return null;
    }

    /**
     * Return routeDecoratorCacheSpec. See {@link Flags#routeDecoratorCacheSpec()}.
     */
    @Nullable
    default String routeDecoratorCacheSpec() {
        return null;
    }

    /**
     * Return parsedPathCacheSpec. See {@link Flags#parsedPathCacheSpec()}.
     */
    @Nullable
    default String parsedPathCacheSpec() {
        return null;
    }

    /**
     * Return headerValueCacheSpec. See {@link Flags#headerValueCacheSpec()}.
     */
    @Nullable
    default String headerValueCacheSpec() {
        return null;
    }

    /**
     * Return cachedHeaders. See {@link Flags#cachedHeaders()}.
     */
    @Nullable
    default List<String> cachedHeaders() {
        return null;
    }

    /**
     * Return fileServiceCacheSpec. See {@link Flags#fileServiceCacheSpec()}.
     */
    @Nullable
    default String fileServiceCacheSpec() {
        return null;
    }

    /**
     * Return dnsCacheSpec. See {@link Flags#dnsCacheSpec()}.
     */
    @Nullable
    default String dnsCacheSpec() {
        return null;
    }

    /**
     * Return preferredIpV4Addresses. See {@link Flags#preferredIpV4Addresses()}.
     */
    @Nullable
    default Predicate<InetAddress> preferredIpV4Addresses() {
        return null;
    }

    /**
     * Return useJdkDnsResolver. See {@link Flags#useJdkDnsResolver()}.
     */
    @Nullable
    default Boolean useJdkDnsResolver() {
        return null;
    }

    /**
     * Return reportBlockedEventLoop. See {@link Flags#reportBlockedEventLoop()}.
     */
    @Nullable
    default Boolean reportBlockedEventLoop() {
        return null;
    }

    /**
     * Return validateHeaders. See {@link Flags#validateHeaders()}.
     */
    @Nullable
    default Boolean validateHeaders() {
        return null;
    }

    /**
     * Return tlsAllowUnsafeCiphers. See {@link Flags#tlsAllowUnsafeCiphers()}.
     */
    @Nullable
    default Boolean tlsAllowUnsafeCiphers() {
        return null;
    }

    /**
     * Return transientServiceOptions. See {@link Flags#transientServiceOptions()}.
     */
    @Nullable
    default Set<TransientServiceOption> transientServiceOptions() {
        return null;
    }

    /**
     * Return useDefaultSocketOptions. See {@link Flags#useDefaultSocketOptions()}.
     */
    @Nullable
    default Boolean useDefaultSocketOptions() {
        return null;
    }

    /**
     * Return useLegacyRouteDecoratorOrdering. See {@link Flags#useLegacyRouteDecoratorOrdering()}.
     */
    @Nullable
    default Boolean useLegacyRouteDecoratorOrdering() {
        return null;
    }

    /**
     * Return allowDoubleDotsInQueryString. See {@link Flags#allowDoubleDotsInQueryString()}.
     */
    @Nullable
    default Boolean allowDoubleDotsInQueryString() {
        return null;
    }
}
