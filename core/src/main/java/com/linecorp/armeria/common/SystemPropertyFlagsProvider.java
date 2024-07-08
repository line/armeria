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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.base.Splitter;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.InetAddressPredicates;
import com.linecorp.armeria.common.util.Sampler;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.server.MultipartRemovalStrategy;
import com.linecorp.armeria.server.TransientServiceOption;

/**
 * Implementation of {@link FlagsProvider} which provides values from JVM options to {@link Flags}.
 */
final class SystemPropertyFlagsProvider implements FlagsProvider {

    static final SystemPropertyFlagsProvider INSTANCE = new SystemPropertyFlagsProvider();

    private static final Logger logger = LoggerFactory.getLogger(SystemPropertyFlagsProvider.class);
    private static final String PREFIX = "com.linecorp.armeria.";
    private static final Splitter CSV_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private SystemPropertyFlagsProvider() {}

    @Override
    public int priority() {
        return Integer.MAX_VALUE;
    }

    @Override
    public String name() {
        return "sysprops";
    }

    @Override
    public Sampler<Class<? extends Throwable>> verboseExceptionSampler() {
        final String spec = getNormalized("verboseExceptions");
        if (spec == null) {
            return null;
        }
        try {
            Sampler.of(spec);
        } catch (Exception e) {
            // Invalid sampler specification
            throw new IllegalArgumentException("invalid sampler spec: " + spec, e);
        }
        return new ExceptionSampler(spec);
    }

    @Override
    public Boolean verboseSocketExceptions() {
        return getBoolean("verboseSocketExceptions");
    }

    @Override
    public Boolean verboseResponses() {
        return getBoolean("verboseResponses");
    }

    @Override
    public RequestContextStorageProvider requestContextStorageProvider() {
        final String providerFqcn = System.getProperty(PREFIX + "requestContextStorageProvider");
        if (providerFqcn == null) {
            return null;
        }
        final List<RequestContextStorageProvider> providers = FlagsUtil.getRequestContextStorageProviders();
        if (providers.isEmpty()) {
            throw new IllegalArgumentException(
                    providerFqcn + " is specified, but no " +
                    RequestContextStorageProvider.class.getSimpleName() + " is found");
        }
        final List<RequestContextStorageProvider> matchedCandidates =
                providers.stream()
                         .filter(provider -> provider.getClass().getName().equals(providerFqcn))
                         .collect(toImmutableList());
        if (matchedCandidates.isEmpty()) {
            throw new IllegalArgumentException(
                    providerFqcn + " does not match any " +
                    RequestContextStorageProvider.class.getSimpleName() + ". providers: " + providers
            );
        }
        if (matchedCandidates.size() > 1) {
            throw new IllegalArgumentException(
                    providerFqcn + " matches more than one " +
                    RequestContextStorageProvider.class.getSimpleName() + ". providers: " + providers);
        }
        return matchedCandidates.get(0);
    }

    @Override
    public Boolean warnNettyVersions() {
        return getBoolean("warnNettyVersions");
    }

    @Override
    public TransportType transportType() {
        final String strTransportType = getNormalized("transportType");
        if (strTransportType == null) {
            return null;
        }
        switch (strTransportType) {
            case "nio":
                return TransportType.NIO;
            case "epoll":
                return TransportType.EPOLL;
            case "io_uring":
                return TransportType.IO_URING;
            default:
                throw new IllegalArgumentException(String.format("%s isn't TransportType", strTransportType));
        }
    }

    @Override
    public Boolean useOpenSsl() {
        return getBoolean("useOpenSsl");
    }

    @Override
    public TlsEngineType tlsEngineType() {
        final String strTlsEngineType = getNormalized("tlsEngineType");
        if (strTlsEngineType == null) {
            return null;
        }
        switch (strTlsEngineType) {
            case "jdk":
                return TlsEngineType.JDK;
            case "openssl":
                return TlsEngineType.OPENSSL;
            default:
                throw new IllegalArgumentException(
                        String.format("%s isn't one of 'jdk' or 'openssl'", strTlsEngineType));
        }
    }

    @Override
    public Boolean dumpOpenSslInfo() {
        return getBoolean("dumpOpenSslInfo");
    }

    @Override
    public Integer maxNumConnections() {
        return getInt("maxNumConnections");
    }

    @Override
    public Integer numCommonWorkers(TransportType transportType) {
        return getInt("numCommonWorkers");
    }

    @Override
    public Integer numCommonBlockingTaskThreads() {
        return getInt("numCommonBlockingTaskThreads");
    }

    @Override
    public Long defaultMaxRequestLength() {
        return getLong("defaultMaxRequestLength");
    }

    @Override
    public Long defaultMaxResponseLength() {
        return getLong("defaultMaxResponseLength");
    }

    @Override
    public Long defaultRequestTimeoutMillis() {
        return getLong("defaultRequestTimeoutMillis");
    }

    @Override
    public Long defaultResponseTimeoutMillis() {
        return getLong("defaultResponseTimeoutMillis");
    }

    @Override
    public Long defaultConnectTimeoutMillis() {
        return getLong("defaultConnectTimeoutMillis");
    }

    @Override
    public Long defaultWriteTimeoutMillis() {
        return getLong("defaultWriteTimeoutMillis");
    }

    @Override
    public Long defaultServerIdleTimeoutMillis() {
        return getLong("defaultServerIdleTimeoutMillis");
    }

    @Override
    public Long defaultClientIdleTimeoutMillis() {
        return getLong("defaultClientIdleTimeoutMillis");
    }

    @Override
    public Integer defaultHttp1MaxInitialLineLength() {
        return getInt("defaultHttp1MaxInitialLineLength");
    }

    @Override
    public Integer defaultHttp1MaxHeaderSize() {
        return getInt("defaultHttp1MaxHeaderSize");
    }

    @Override
    public Integer defaultHttp1MaxChunkSize() {
        return getInt("defaultHttp1MaxChunkSize");
    }

    @Override
    public Boolean defaultUseHttp2Preface() {
        return getBoolean("defaultUseHttp2Preface");
    }

    @Override
    public Boolean defaultPreferHttp1() {
        return getBoolean("preferHttp1");
    }

    @Override
    public Boolean defaultUseHttp2WithoutAlpn() {
        return getBoolean("defaultUseHttp2WithoutAlpn");
    }

    @Override
    public Boolean defaultUseHttp1Pipelining() {
        return getBoolean("defaultUseHttp1Pipelining");
    }

    @Override
    public Long defaultPingIntervalMillis() {
        return getLong("defaultPingIntervalMillis");
    }

    @Override
    public Integer defaultMaxServerNumRequestsPerConnection() {
        return getInt("defaultMaxServerNumRequestsPerConnection");
    }

    @Override
    public Integer defaultMaxClientNumRequestsPerConnection() {
        return getInt("defaultMaxClientNumRequestsPerConnection");
    }

    @Override
    public Long defaultMaxServerConnectionAgeMillis() {
        return getLong("defaultMaxServerConnectionAgeMillis");
    }

    @Override
    public Long defaultMaxClientConnectionAgeMillis() {
        return getLong("defaultMaxClientConnectionAgeMillis");
    }

    @Override
    public Long defaultServerConnectionDrainDurationMicros() {
        return getLong("defaultServerConnectionDrainDurationMicros");
    }

    @Override
    public Long defaultClientHttp2GracefulShutdownTimeoutMillis() {
        return getLong("defaultClientHttp2GracefulShutdownTimeoutMillis");
    }

    @Override
    public Integer defaultHttp2InitialConnectionWindowSize() {
        return getInt("defaultHttp2InitialConnectionWindowSize");
    }

    @Override
    public Integer defaultHttp2InitialStreamWindowSize() {
        return getInt("defaultHttp2InitialStreamWindowSize");
    }

    @Override
    public Integer defaultHttp2MaxFrameSize() {
        return getInt("defaultHttp2MaxFrameSize");
    }

    @Override
    public Long defaultHttp2MaxStreamsPerConnection() {
        return getLong("defaultHttp2MaxStreamsPerConnection");
    }

    @Override
    public Long defaultHttp2MaxHeaderListSize() {
        return getLong("defaultHttp2MaxHeaderListSize");
    }

    @Override
    public Integer defaultServerHttp2MaxResetFramesPerMinute() {
        return getInt("defaultServerHttp2MaxResetFramesPerMinute");
    }

    @Override
    public String defaultBackoffSpec() {
        return getNormalized("defaultBackoffSpec");
    }

    @Override
    public Integer defaultMaxTotalAttempts() {
        return getInt("defaultMaxTotalAttempts");
    }

    @Override
    public @Nullable Long defaultRequestAutoAbortDelayMillis() {
        return getLong("defaultRequestAutoAbortDelayMillis");
    }

    @Override
    public String routeCacheSpec() {
        return getNormalized("routeCacheSpec");
    }

    @Override
    public String routeDecoratorCacheSpec() {
        return getNormalized("routeDecoratorCacheSpec");
    }

    @Override
    public String parsedPathCacheSpec() {
        return getNormalized("parsedPathCacheSpec");
    }

    @Override
    public String headerValueCacheSpec() {
        return getNormalized("headerValueCacheSpec");
    }

    @Override
    public List<String> cachedHeaders() {
        final String val = getNormalized("cachedHeaders");
        if (val == null) {
            return null;
        }
        return CSV_SPLITTER.splitToList(val);
    }

    @Override
    public String fileServiceCacheSpec() {
        return getNormalized("fileServiceCacheSpec");
    }

    @Override
    public String dnsCacheSpec() {
        return getNormalized("dnsCacheSpec");
    }

    @Override
    public Predicate<InetAddress> preferredIpV4Addresses() {
        final String val = getNormalized("preferredIpV4Addresses");
        if (val == null) {
            return null;
        }
        final List<Predicate<InetAddress>> preferredIpV4Addresses =
                CSV_SPLITTER.splitToList(val)
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
                            .collect(Collectors.toList());
        switch (preferredIpV4Addresses.size()) {
            case 0:
                return null;
            case 1:
                return preferredIpV4Addresses.get(0);
            default:
                return inetAddress -> {
                    for (Predicate<InetAddress> preferredIpV4Addr : preferredIpV4Addresses) {
                        if (preferredIpV4Addr.test(inetAddress)) {
                            return true;
                        }
                    }
                    return false;
                };
        }
    }

    @Override
    public Boolean useJdkDnsResolver() {
        return getBoolean("useJdkDnsResolver");
    }

    @Override
    public Boolean reportBlockedEventLoop() {
        return getBoolean("reportBlockedEventLoop");
    }

    @Override
    public Boolean reportMaskedRoutes() {
        return getBoolean("reportMaskedRoutes");
    }

    @Override
    public Boolean validateHeaders() {
        return getBoolean("validateHeaders");
    }

    @Override
    public Boolean tlsAllowUnsafeCiphers() {
        return getBoolean("tlsAllowUnsafeCiphers");
    }

    @Override
    public Integer defaultMaxClientHelloLength() {
        return getInt("defaultMaxClientHelloLength");
    }

    @Override
    public Set<TransientServiceOption> transientServiceOptions() {
        final String val = getNormalized("transientServiceOptions");
        if (val == null) {
            return null;
        }
        return Sets.immutableEnumSet(
                Streams.stream(CSV_SPLITTER.split(val))
                       .map(feature -> TransientServiceOption.valueOf(Ascii.toUpperCase(feature)))
                       .collect(toImmutableSet()));
    }

    @Override
    public Boolean useDefaultSocketOptions() {
        return getBoolean("useDefaultSocketOptions");
    }

    @Override
    public Boolean useLegacyRouteDecoratorOrdering() {
        return getBoolean("useLegacyRouteDecoratorOrdering");
    }

    @Override
    public Boolean allowDoubleDotsInQueryString() {
        return getBoolean("allowDoubleDotsInQueryString");
    }

    @Override
    public Boolean allowSemicolonInPathComponent() {
        return getBoolean("allowSemicolonInPathComponent");
    }

    @Override
    public Path defaultMultipartUploadsLocation() {
        return getAndParse("defaultMultipartUploadsLocation", Paths::get);
    }

    @Nullable
    @Override
    public MultipartRemovalStrategy defaultMultipartRemovalStrategy() {
        final String multipartRemovalStrategy = getNormalized("defaultMultipartRemovalStrategy");
        if (multipartRemovalStrategy == null) {
            return null;
        }
        switch (multipartRemovalStrategy) {
            case "never":
                return MultipartRemovalStrategy.NEVER;
            case "on_response_completion":
                return MultipartRemovalStrategy.ON_RESPONSE_COMPLETION;
            default:
                throw new IllegalArgumentException(
                        multipartRemovalStrategy + " isn't a MultipartRemovalStrategy");
        }
    }

    @Override
    public Sampler<? super RequestContext> requestContextLeakDetectionSampler() {
        final String spec = getNormalized("requestContextLeakDetectionSampler");
        if (spec == null) {
            return null;
        }
        try {
            return Sampler.of(spec);
        } catch (Exception e) {
            // Invalid sampler specification
            throw new IllegalArgumentException("invalid sampler spec: " + spec, e);
        }
    }

    @Override
    public Long defaultUnhandledExceptionsReportIntervalMillis() {
        return getLong("defaultUnhandledExceptionsReportIntervalMillis");
    }

    @Override
    public Long defaultHttp1ConnectionCloseDelayMillis() {
        return getLong("defaultHttp1ConnectionCloseDelayMillis");
    }

    @Override
    public @Nullable Long defaultUnloggedExceptionsReportIntervalMillis() {
        return getLong("defaultUnloggedExceptionsReportIntervalMillis");
    }

    @Nullable
    private static Long getLong(String name) {
        return getAndParse(name, Long::parseLong);
    }

    @Nullable
    private static Integer getInt(String name) {
        return getAndParse(name, Integer::parseInt);
    }

    @Nullable
    private static Boolean getBoolean(String name) {
        return getAndParse(name, SystemPropertyFlagsProvider::strictlyParseBoolean);
    }

    private static Boolean strictlyParseBoolean(String val) {
        if (!val.equals(Boolean.TRUE.toString()) && !val.equals(Boolean.FALSE.toString())) {
            throw new IllegalArgumentException(String.format("%s not in \"true\" or \"false\"", val));
        }
        return Boolean.valueOf(val);
    }

    @Nullable
    private static <T> T getAndParse(String name, Function<String, T> parser) {
        final String val = getNormalized(name);
        if (val == null) {
            return null;
        }
        return parser.apply(val);
    }

    @Nullable
    private static String getNormalized(String name) {
        final String fullName = PREFIX + name;
        String value = System.getProperty(fullName);
        if (value != null) {
            value = Ascii.toLowerCase(value);
        }
        return value;
    }
}
