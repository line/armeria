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

import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.DnsCache;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.util.TransportType;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsResponse;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.resolver.dns.DnsServerAddresses;

class DefaultDnsResolverTest {

    /**
     * This test does not check anything but makes the future returned by
     * {@link DefaultDnsResolver#resolve(List, String)}} is completed even before the DNS responses
     * are received. By doing so, we can make our leak detectors detect any leaks if we forgot to release
     * the DNS responses. See https://github.com/line/armeria/pull/2951 for more information.
     */
    @Test
    void responseReceivedAfterCancellation() throws Exception {
        try (TestDnsServer dnsServer = new TestDnsServer(
                ImmutableMap.of(new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("foo.com.", "1.2.3.4")),
                                new DefaultDnsQuestion("bar.com.", DnsRecordType.A),
                                new DefaultDnsResponse(0).addRecord(DnsSection.ANSWER,
                                                                    newAddressRecord("bar.com.", "5.6.7.8"))),
                new ChannelOutboundHandlerAdapter() {
                    @Override
                    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                        ctx.executor().schedule(() -> ctx.writeAndFlush(msg, promise), 1, TimeUnit.SECONDS);
                    }
                })) {

            final EventLoop eventLoop = CommonPools.workerGroup().next();
            final DefaultDnsResolver resolver =
                    DefaultDnsResolver.of(
                            new DnsNameResolverBuilder(eventLoop)
                                    .channelType(TransportType.datagramChannelType(eventLoop))
                                    .queryTimeoutMillis(Long.MAX_VALUE)
                                    .nameServerProvider(
                                            name -> DnsServerAddresses.sequential(dnsServer.addr()).stream())
                                    .build(),
                            DnsCache.of(), eventLoop, ImmutableList.of(), 1, 1);

            final DnsQuestionContext ctx = new DnsQuestionContext(eventLoop, 1, true);
            resolver.resolveAll(ctx,
                                ImmutableList.of(new DefaultDnsQuestion("foo.com.", DnsRecordType.A),
                                                 new DefaultDnsQuestion("bar.com.", DnsRecordType.A)),
                                "");

            // Cancel the queries immediately.
            ctx.whenCancelled().cancel(false);

            // Wait until the DNS server sends the response.
            Thread.sleep(2000);
            resolver.close();
        }
    }
}
