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

import java.net.InetAddress;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Default implementation of {@link FlagsProvider} which provides default values to {@link Flags}.
 */
public final class DefaultFlagsProvider implements FlagsProvider {

    @Override
    public int priority() {
        return Integer.MIN_VALUE;
    }

    @Override
    public Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        return new ExceptionSampler("rate-limit=10");
    }

    @Override
    public String verboseExceptionSamplerSpec() {
        return "rate-limit=10";
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

    @Override
    public Integer maxNumConnections() {
        return Integer.MAX_VALUE;
    }

    @Override
    public Integer numCommonWorkers() {
        final int defaultNumCpuCores = Runtime.getRuntime().availableProcessors();
        return defaultNumCpuCores * 2;
    }

    @Override
    public Integer numCommonBlockingTaskThreads() {
        return 200; // from Tomcat default maxThreads
    }

    @Override
    public Long defaultMaxRequestLength() {
        return Long.valueOf(10 * 1024 * 1024); // 10 MiB
    }

    @Override
    public Long defaultMaxResponseLength() {
        return Long.valueOf(10 * 1024 * 1024); // 10 MiB
    }

    @Override
    public Long defaultRequestTimeoutMillis() {
        return Long.valueOf(10 * 1000); // 10 seconds
    }

    @Override
    public Long defaultResponseTimeoutMillis() {
        return Long.valueOf(15 * 1000); // 15 seconds
    }

    @Override
    public Long defaultConnectTimeoutMillis() {
        return 3200L; // 3.2 seconds
    }

    @Override
    public Long defaultWriteTimeoutMillis() {
        return 1000L; // 1 second
    }

    @Override
    public Long defaultServerIdleTimeoutMillis() {
        return 15000L; // 15 seconds
    }

    @Override
    public Long defaultClientIdleTimeoutMillis() {
        return 10000L; // 10 seconds
    }

    @Override
    public Integer defaultHttp1MaxInitialLineLength() {
        return 4096; // from Netty
    }

    @Override
    public Integer defaultHttp1MaxHeaderSize() {
        return 8192; // from Netty
    }

    @Override
    public Integer defaultHttp1MaxChunkSize() {
        return 8192; // from Netty
    }

    @Override
    public Boolean defaultUseHttp2Preface() {
        return true;
    }

    @Override
    public Boolean defaultUseHttp1Pipelining() {
        return false;
    }

    @Override
    public Long defaultPingIntervalMillis() {
        return 0L; // Disabled
    }

    @Override
    public Integer defaultMaxServerNumRequestsPerConnection() {
        return 0; // Disabled
    }

    @Override
    public Integer defaultMaxClientNumRequestsPerConnection() {
        return 0; // Disabled
    }

    @Override
    public Long defaultMaxServerConnectionAgeMillis() {
        return 0L; // Disabled
    }

    @Override
    public Long defaultMaxClientConnectionAgeMillis() {
        return 0L; // Disabled
    }

    @Override
    public Long defaultServerConnectionDrainDurationMicros() {
        return 1000000L;
    }

    @Override
    public Integer defaultHttp2InitialConnectionWindowSize() {
        return 1024 * 1024; // 1MiB
    }

    @Override
    public Integer defaultHttp2InitialStreamWindowSize() {
        return 1024 * 1024; // 1MiB
    }

    @Override
    public Integer defaultHttp2MaxFrameSize() {
        return 16384; // From HTTP/2 specification
    }

    @Override
    public Long defaultHttp2MaxStreamsPerConnection() {
        return Long.valueOf(Integer.MAX_VALUE);
    }

    @Override
    public Long defaultHttp2MaxHeaderListSize() {
        return 8192L; // from Netty default maxHeaderSize
    }

    @Override
    public String defaultBackoffSpec() {
        return "exponential=200:10000,jitter=0.2";
    }

    @Override
    public Integer defaultMaxTotalAttempts() {
        return 10;
    }

    @Override
    public String routeCacheSpec() {
        return "maximumSize=4096";
    }

    @Override
    public String routeDecoratorCacheSpec() {
        return "maximumSize=4096";
    }

    @Override
    public String parsedPathCacheSpec() {
        return "maximumSize=4096";
    }

    @Override
    public String headerValueCacheSpec() {
        return "maximumSize=4096";
    }

    @Override
    public List<String> cachedHeaders() {
        return Splitter.on(',').trimResults()
                       .omitEmptyStrings()
                       .splitToList(":authority,:scheme,:method,accept-encoding,content-type");
    }

    @Override
    public String fileServiceCacheSpec() {
        return "maximumSize=1024";
    }

    @Override
    public String dnsCacheSpec() {
        return "maximumSize=4096";
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
}
