/*
 * Copyright 2018 LINE Corporation
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

import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsRecordType.AAAA;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Map.Entry;

import javax.annotation.Nullable;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.internal.common.util.TransportType;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.dns.DatagramDnsQuery;
import io.netty.handler.codec.dns.DatagramDnsQueryDecoder;
import io.netty.handler.codec.dns.DatagramDnsResponse;
import io.netty.handler.codec.dns.DatagramDnsResponseEncoder;
import io.netty.handler.codec.dns.DefaultDnsRawRecord;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;

public final class TestDnsServer implements AutoCloseable {

    public static DnsRecord newAddressRecord(String name, String ipAddr) {
        return newAddressRecord(name, ipAddr, 60);
    }

    public static DnsRecord newAddressRecord(String name, String ipAddr, long ttl) {
        return new DefaultDnsRawRecord(
                name, NetUtil.isValidIpV4Address(ipAddr) ? A : AAAA,
                ttl, Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer(
                NetUtil.createByteArrayFromIpAddressString(ipAddr))));
    }

    private final Channel channel;
    private volatile Map<DnsQuestion, DnsResponse> responses;

    public TestDnsServer(Map<DnsQuestion, DnsResponse> responses) {
        this(responses, null);
    }

    public TestDnsServer(Map<DnsQuestion, DnsResponse> responses,
                         @Nullable ChannelHandler beforeDnsServerHandler) {
        this.responses = ImmutableMap.copyOf(responses);

        final Bootstrap b = new Bootstrap();
        b.channel(TransportType.datagramChannelType(CommonPools.workerGroup()));
        b.group(CommonPools.workerGroup());
        b.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) throws Exception {
                final ChannelPipeline p = ch.pipeline();
                p.addLast(new DatagramDnsQueryDecoder());
                p.addLast(new DatagramDnsResponseEncoder());
                if (beforeDnsServerHandler != null) {
                    p.addLast(beforeDnsServerHandler);
                }
                p.addLast(new DnsServerHandler());
            }
        });

        channel = b.bind(NetUtil.LOCALHOST, 0).syncUninterruptibly().channel();
    }

    public InetSocketAddress addr() {
        return (InetSocketAddress) channel.localAddress();
    }

    public void setResponses(Map<DnsQuestion, DnsResponse> responses) {
        this.responses.values().forEach(DnsResponse::release);
        this.responses = ImmutableMap.copyOf(responses);
    }

    @Override
    public void close() {
        if (!channel.isOpen()) {
            return;
        }

        channel.close().syncUninterruptibly();
        responses.values().forEach(DnsResponse::release);
    }

    private class DnsServerHandler extends SimpleChannelInboundHandler<DatagramDnsQuery> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramDnsQuery query) {
            final DnsQuestion question = query.recordAt(DnsSection.QUESTION, 0);
            boolean responded = false;
            for (Entry<DnsQuestion, DnsResponse> e : responses.entrySet()) {
                final DnsQuestion q = e.getKey();
                final DnsResponse r = e.getValue();
                if (question.dnsClass() == q.dnsClass() &&
                    question.name().equals(q.name()) &&
                    question.type() == q.type()) {

                    final DnsResponse res = new DatagramDnsResponse(
                            null, query.sender(), query.id(), query.opCode(), r.code());

                    if (r.count(DnsSection.QUESTION) == 0) {
                        res.addRecord(DnsSection.QUESTION, question);
                    }
                    for (DnsSection section : DnsSection.values()) {
                        final int count = r.count(section);
                        for (int i = 0; i < count; i++) {
                            res.addRecord(section, ReferenceCountUtil.retain(r.recordAt(section, i)));
                        }
                    }

                    ctx.writeAndFlush(res);
                    responded = true;
                }
            }

            if (!responded) {
                final DnsResponse res = new DatagramDnsResponse(
                        null, query.sender(), query.id(), query.opCode(), DnsResponseCode.NXDOMAIN);
                res.addRecord(DnsSection.QUESTION, question);
                ctx.writeAndFlush(res);
            }
        }
    }
}
