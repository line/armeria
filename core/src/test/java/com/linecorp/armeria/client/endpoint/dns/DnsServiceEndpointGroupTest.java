/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.endpoint.dns;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.testing.internal.AnticipatedException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DefaultDnsRecordEncoder;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsOpCode;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolver;

public class DnsServiceEndpointGroupTest {

    private static final List<Endpoint> ENDPOINTS =
            ImmutableList.of(
                    Endpoint.of("1.armeria.com", 443, 1),
                    Endpoint.of("2.armeria.com", 8080, 1),
                    Endpoint.of("3.armeria.com", 9000, 5));

    private static EventLoop EVENT_LOOP;

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private AddressedEnvelope<DnsResponse, InetSocketAddress> envelope;

    @Mock
    private DnsNameResolver resolver;

    @BeforeClass
    public static void setUpEventLoop() {
        EVENT_LOOP = new DefaultEventLoop(Executors.newSingleThreadExecutor());
    }

    @AfterClass
    public static void tearDownEventLoop() {
        EVENT_LOOP.shutdownGracefully(0, 10, TimeUnit.SECONDS);
    }

    @Before
    public void setUp() {
        when(resolver.query(isA(DnsQuestion.class)))
                .thenReturn(EVENT_LOOP.newSucceededFuture(envelope));
    }

    @Test
    public void normal() throws Exception {
        when(envelope.content()).thenReturn(createSrvResponse());

        DnsServiceEndpointGroup endpointGroup = new DnsServiceEndpointGroup(
                "armeria.com",
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        endpointGroup.query();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS));
    }

    @Test
    public void notFound() throws Exception {
        when(envelope.content()).thenReturn(createSrvResponse());
        DnsServiceEndpointGroup endpointGroup = new DnsServiceEndpointGroup(
                "armeria.com",
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        endpointGroup.query();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS));

        DefaultDnsResponse notFoundResponse = new DefaultDnsResponse(
                2, DnsOpCode.QUERY, DnsResponseCode.NXDOMAIN);
        when(envelope.content()).thenReturn(notFoundResponse);
        endpointGroup.query();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).isEmpty());
    }

    @Test
    public void unexpectedError() throws Exception {
        when(envelope.content()).thenReturn(createSrvResponse());
        DnsServiceEndpointGroup endpointGroup = new DnsServiceEndpointGroup(
                "armeria.com",
                resolver,
                EVENT_LOOP,
                Duration.ofSeconds(1));
        endpointGroup.query();
        await().untilAsserted(() -> assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS));

        when(resolver.query(isA(DnsQuestion.class))).thenReturn(
                EVENT_LOOP.newFailedFuture(new AnticipatedException("failed")));
        endpointGroup.query();
        // Unexpected errors ignored, do a small sleep to give time for the future to resolve.
        Thread.sleep(TimeUnit.SECONDS.toMillis(1));
        assertThat(endpointGroup.endpoints()).containsExactlyElementsOf(ENDPOINTS);
    }

    private static DnsResponse createSrvResponse() throws Exception {
        DefaultDnsResponse dnsResponse = new DefaultDnsResponse(1);
        for (Endpoint endpoint : ENDPOINTS) {
            dnsResponse.addRecord(DnsSection.ANSWER,
                                  createSrvRecord("armeria.com", endpoint.host(), endpoint.port(),
                                                  endpoint.weight()));
        }
        // Skipped records
        dnsResponse.addRecord(DnsSection.ADDITIONAL, createSrvRecord("armeria.com", "foo", 80, 1));
        dnsResponse.addRecord(DnsSection.ANSWER, new DefaultDnsRawRecord("armeria.com", DnsRecordType.A, 200,
                                                                         Unpooled.buffer()));
        dnsResponse.addRecord(DnsSection.ANSWER, createSrvRecord("barmeria.com", "foo", 80, 1));
        dnsResponse.addRecord(DnsSection.ANSWER, mock(DnsRecord.class));

        return dnsResponse;
    }

    private static DnsRecord createSrvRecord(String query, String target, int port, int weight)
            throws Exception {
        ByteBuf content = Unpooled.buffer();
        content.writeShort(1); // priority unused
        content.writeShort(weight);
        content.writeShort(port);
        DefaultDnsRecordEncoderTrampoline.INSTANCE.encodeName(target, content);
        return new DefaultDnsRawRecord(query, DnsRecordType.SRV, 200, content);
    }

    // Hacky trampoline class to be able to access encodeName
    private static class DefaultDnsRecordEncoderTrampoline extends DefaultDnsRecordEncoder {

        private static final DefaultDnsRecordEncoderTrampoline INSTANCE =
                new DefaultDnsRecordEncoderTrampoline();

        @Override
        protected void encodeName(String name, ByteBuf buf) throws Exception {
            super.encodeName(name, buf);
        }
    }
}
