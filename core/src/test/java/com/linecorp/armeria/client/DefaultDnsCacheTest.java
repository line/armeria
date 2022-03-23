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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.internal.client.dns.ByteArrayDnsRecord;
import com.linecorp.armeria.internal.client.dns.DnsQuestionWithoutTrailingDot;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;

class DefaultDnsCacheTest {

    private static List<DnsRecord> records;
    final DnsQuestionWithoutTrailingDot query = DnsQuestionWithoutTrailingDot.of("foo.com.", DnsRecordType.A);

    @BeforeAll
    static void beforeAll() throws UnknownHostException {
        final ByteBuf address0 = Unpooled.wrappedBuffer(new byte[]{ 1, 1, 1, 0 });
        final ByteBuf address1 = Unpooled.wrappedBuffer(new byte[]{ 1, 1, 1, 1 });
        final DnsRawRecord record0 = new DefaultDnsRawRecord("foo.com.", DnsRecordType.A, 2, address0);
        final DnsRawRecord record1 = new DefaultDnsRawRecord("foo.com.", DnsRecordType.A, 3, address1);
        records = ImmutableList.of(ByteArrayDnsRecord.copyOf(record0), ByteArrayDnsRecord.copyOf(record1));
        assertThat(record1.refCnt()).isZero();
    }

    static DnsRecord newRecord(String name, String ipAddress, int ttl) throws UnknownHostException {
        final ByteBuf buf = Unpooled.wrappedBuffer(InetAddress.getByName(ipAddress).getAddress());
        final DnsRawRecord record = new DefaultDnsRawRecord(name, DnsRecordType.A, ttl, buf);
        final DnsRecord copied = ByteArrayDnsRecord.copyOf(record);
        assertThat(record.refCnt()).isZero();
        return copied;
    }

    @Test
    void success() throws UnknownHostException {
        final DnsCache dnsCache = DnsCache.builder().ttl(1, 5).build();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final DnsRecord record0 = newRecord("foo.com.", "1.1.1.0", 2);
        final DnsRecord record1 = newRecord("foo.com.", "1.1.1.1", 4);
        final List<DnsRecord> records = ImmutableList.of(record0, record1);

        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);
        final AtomicReference<List<DnsRecord>> removed = new AtomicReference<>();
        dnsCache.addListener((key, result, cause) -> {
            assertThat(key).isEqualTo(query);
            assertThat(cause).isNull();
            removed.set(result);
        });

        await().untilAtomic(removed, Matchers.is(records));
        // Should use 2 seconds as the TTL.
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isBetween(1000L, 3000L);
        assertThat(dnsCache.get(query)).isNull();

        stopwatch.reset().start();
        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);
        removed.set(null);
        dnsCache.addListener((key, result, cause) -> {
            assertThat(key.name()).isEqualTo("foo.com.");
            assertThat(cause).isNull();
            removed.set(result);
        });
        await().untilAtomic(removed, Matchers.is(records));
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isBetween(1000L, 4000L);
        assertThat(dnsCache.get(query)).isNull();
    }

    @Test
    void boundByCacheTtl() throws UnknownHostException {
        final DnsCache dnsCache = DnsCache.builder().ttl(1, 2).build();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final DnsRecord record0 = newRecord("foo.com.", "1.1.1.0", 20);
        final DnsRecord record1 = newRecord("foo.com.", "1.1.1.1", 40);
        final List<DnsRecord> records = ImmutableList.of(record0, record1);

        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);
        final AtomicReference<List<DnsRecord>> removed = new AtomicReference<>();
        dnsCache.addListener((key, result, cause) -> {
            assertThat(key).isEqualTo(query);
            assertThat(cause).isNull();
            removed.set(result);
        });

        await().untilAtomic(removed, Matchers.is(records));
        // Should use 2 seconds as the TTL.
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isBetween(1000L, 3000L);
        assertThat(dnsCache.get(query)).isNull();
    }

    @Test
    void unknownHost() throws UnknownHostException {
        final DnsCache dnsCache = DnsCache.builder().negativeTtl(3).build();
        final Stopwatch stopwatch = Stopwatch.createStarted();
        final UnknownHostException unknownHostException = new UnknownHostException("not found");
        dnsCache.cache(query, unknownHostException);
        assertThatThrownBy(() -> dnsCache.get(query)).isSameAs(unknownHostException);
        final AtomicReference<UnknownHostException> removed = new AtomicReference<>();
        dnsCache.addListener((key, result, cause) -> {
            assertThat(key.name()).isEqualTo("foo.com.");
            assertThat(result).isNull();
            removed.set(cause);
        });
        await().untilAtomic(removed, Matchers.is(unknownHostException));
        assertThat(stopwatch.elapsed(TimeUnit.MILLISECONDS)).isBetween(2000L, 4000L);
        assertThat(dnsCache.get(query)).isNull();
    }

    @Test
    void remove() throws UnknownHostException {
        final DnsCache dnsCache = DnsCache.builder().ttl(Integer.MAX_VALUE, Integer.MAX_VALUE).build();
        final DnsRecord record0 = newRecord("foo.com.", "1.1.1.0", 20);
        final DnsRecord record1 = newRecord("foo.com.", "1.1.1.1", 40);
        final List<DnsRecord> records = ImmutableList.of(record0, record1);
        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);
        final AtomicReference<List<DnsRecord>> removed = new AtomicReference<>();
        dnsCache.addListener((key, result, cause) -> {
            assertThat(key).isEqualTo(query);
            assertThat(cause).isNull();
            removed.set(result);
        });
        dnsCache.remove(query);
        await().untilAsserted(() -> {
            assertThat(removed).hasValue(records);
        });
        assertThat(dnsCache.get(query)).isNull();
    }

    @Test
    void removeAll() throws UnknownHostException {
        final DnsCache dnsCache = DnsCache.builder().ttl(Integer.MAX_VALUE, Integer.MAX_VALUE).build();
        final DnsRecord record0 = newRecord("foo.com.", "1.1.1.0", 20);
        final DnsRecord record1 = newRecord("bar.com.", "1.1.1.1", 40);

        final DnsQuestionWithoutTrailingDot barQuery =
                DnsQuestionWithoutTrailingDot.of("bar.com.", DnsRecordType.A);
        dnsCache.cache(query, ImmutableList.of(record0));
        dnsCache.cache(barQuery, ImmutableList.of(record1));

        final UnknownHostException unknownHostException = new UnknownHostException("not found");
        final DnsQuestionWithoutTrailingDot quxQuery =
                DnsQuestionWithoutTrailingDot.of("qux.com.", DnsRecordType.A);
        dnsCache.cache(quxQuery, unknownHostException);
        dnsCache.removeAll();
        assertThat(dnsCache.get(query)).isNull();
        assertThat(dnsCache.get(barQuery)).isNull();
        assertThat(dnsCache.get(quxQuery)).isNull();
    }

    @Test
    void overrideExistingCache() throws UnknownHostException {
        final DnsCache dnsCache =
                DnsCache.builder()
                        .ttl(Integer.MAX_VALUE, Integer.MAX_VALUE)
                        .negativeTtl(Integer.MAX_VALUE)
                        .build();
        final DnsRecord record0 = newRecord("foo.com.", "1.1.1.0", 20);
        final DnsRecord record1 = newRecord("bar.com.", "1.1.1.1", 40);
        final List<DnsRecord> records = ImmutableList.of(record0, record1);

        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);

        final UnknownHostException unknownHostException = new UnknownHostException("not found");
        dnsCache.cache(query, unknownHostException);
        assertThatThrownBy(() -> dnsCache.get(query)).isSameAs(unknownHostException);

        dnsCache.cache(query, records);
        assertThat(dnsCache.get(query)).isEqualTo(records);

        dnsCache.remove(query);
        assertThat(dnsCache.get(query)).isNull();
    }
}
