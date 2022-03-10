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
import java.util.List;

import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.buffer.ByteBufUtil;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;

public final class DnsUtil {

    private static final List<String> DEFAULT_SEARCH_DOMAINS;
    private static final int DEFAULT_NDOTS;

    static {
        try {
            // TODO(ikhoon): Fork Netty code to avoid reflections for the default options.
            final EventLoop eventLoop = CommonPools.workerGroup().next();
            final DnsNameResolver defaultResolver =
                    new DnsNameResolverBuilder(eventLoop)
                            .channelType(TransportType.datagramChannelType(eventLoop.parent()))
                            .build();
            final Method searchDomainsMethod = DnsNameResolver.class.getDeclaredMethod("searchDomains");
            searchDomainsMethod.setAccessible(true);
            final String[] searchDomains = (String[]) searchDomainsMethod.invoke(defaultResolver);
            DEFAULT_SEARCH_DOMAINS = ImmutableList.copyOf(searchDomains);
            final Method ndotsMethod = DnsNameResolver.class.getDeclaredMethod("ndots");
            ndotsMethod.setAccessible(true);
            DEFAULT_NDOTS = (int) ndotsMethod.invoke(defaultResolver);
        } catch (NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
            throw new Error(e);
        }
    }

    @Nullable
    public static byte[] extractAddressBytes(DnsRecord record, Logger logger, String logPrefix) {
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

    private DnsUtil() {}
}
