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

package com.linecorp.armeria.internal.client.dns;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.DnsTimeoutException;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsErrorCauseException;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsNameResolverTimeoutException;

public final class DnsUtil {

    private static final List<String> DEFAULT_SEARCH_DOMAINS;
    private static final int DEFAULT_NDOTS;
    // Leave it public to inline the value with Javadoc {@value ..} tag.
    public static final int DEFAULT_DNS_QUERY_TIMEOUT_MILLIS = 5000; // 5 seconds

    private static final Logger logger = LoggerFactory.getLogger(DnsUtil.class);

    static {

        final EventLoop eventLoop = CommonPools.workerGroup().next();
        final DnsNameResolver defaultResolver =
                new DnsNameResolverBuilder(eventLoop)
                        .channelType(TransportType.datagramChannelType(eventLoop.parent()))
                        .build();
        List<String> defaultSearchDomain;
        try {
            // TODO(ikhoon): Fork Netty code to avoid reflections for the default options.
            final Method searchDomainsMethod = DnsNameResolver.class.getDeclaredMethod("searchDomains");
            searchDomainsMethod.setAccessible(true);
            final String[] searchDomains = (String[]) searchDomainsMethod.invoke(defaultResolver);
            defaultSearchDomain = ImmutableList.copyOf(searchDomains);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            logger.warn("Failed to get the default searchDomain value through reflection; " +
                        "search domain resolution is disabled.", e);
            defaultSearchDomain = ImmutableList.of();
        }
        DEFAULT_SEARCH_DOMAINS = defaultSearchDomain;

        int ndots;
        try {
            final Method ndotsMethod;
            ndotsMethod = DnsNameResolver.class.getDeclaredMethod("ndots");
            ndotsMethod.setAccessible(true);
            ndots = (int) ndotsMethod.invoke(defaultResolver);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            logger.warn("Failed to get the default ndots value through reflection; using 0 instead.", e);
            ndots = 0;
        }
        DEFAULT_NDOTS = ndots;
    }

    public static byte @Nullable [] extractAddressBytes(DnsRecord record, Logger logger, String logPrefix) {
        final DnsRecordType type = record.type();
        assert record instanceof ByteArrayDnsRecord;
        final byte[] content = ((ByteArrayDnsRecord) record).content();
        final int contentLen = content.length;

        // Skip invalid records.
        if (type == DnsRecordType.A) {
            if (contentLen != 4) {
                warnInvalidRecord(logger, logPrefix, type, content);
                return null;
            }
        } else if (type == DnsRecordType.AAAA) {
            if (contentLen != 16) {
                warnInvalidRecord(logger, logPrefix, type, content);
                return null;
            }
        } else {
            return null;
        }

        return content;
    }

    /**
     * Logs a warning message about an invalid record.
     */
    public static void warnInvalidRecord(Logger logger, String logPrefix, DnsRecordType type, byte[] content) {
        if (logger.isWarnEnabled()) {
            final String dump = ByteBufUtil.hexDump(content);
            logger.warn("{} Skipping invalid {} record: {}",
                        logPrefix, type.name(), dump.isEmpty() ? "<empty>" : dump);
        }
    }

    public static List<String> defaultSearchDomains() {
        return DEFAULT_SEARCH_DOMAINS;
    }

    public static int defaultNdots() {
        return DEFAULT_NDOTS;
    }

    public static long defaultDnsQueryTimeoutMillis() {
        return DEFAULT_DNS_QUERY_TIMEOUT_MILLIS;
    }

    public static boolean isDnsQueryTimedOut(Throwable cause) {
        final Throwable rootCause = Throwables.getRootCause(cause);
        if (rootCause instanceof DnsErrorCauseException) {
            return false;
        }
        if (rootCause instanceof DnsTimeoutException ||
            rootCause instanceof DnsNameResolverTimeoutException) {
            return true;
        }

        if (rootCause instanceof UnknownHostException) {
            for (Throwable suppressedCause : rootCause.getSuppressed()) {
                final Throwable suppressedRootCause = Throwables.getRootCause(suppressedCause);
                if (suppressedRootCause instanceof DnsNameResolverTimeoutException) {
                    return true;
                }
            }
        }

        return false;
    }

    private DnsUtil() {}
}
