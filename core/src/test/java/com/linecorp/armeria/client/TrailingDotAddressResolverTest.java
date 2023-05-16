package com.linecorp.armeria.client;

import com.google.common.collect.ImmutableMap;
import com.linecorp.armeria.client.endpoint.dns.TestDnsServer;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.client.dns.ByteArrayDnsRecord;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.dns.*;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingDeque;

import static com.linecorp.armeria.client.endpoint.dns.TestDnsServer.newAddressRecord;
import static io.netty.handler.codec.dns.DnsRecordType.A;
import static io.netty.handler.codec.dns.DnsSection.ANSWER;
import static org.assertj.core.api.Assertions.assertThat;

class TrailingDotAddressResolverTest {

    @RegisterExtension
    static final EventLoopExtension eventLoopExtension = new EventLoopExtension();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("Hello, world!"));
        }
    };

    private static class DnsRecordCaptor extends ChannelInboundHandlerAdapter {
        private Queue<DnsRecord> records = new LinkedBlockingDeque<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof DatagramDnsQuery) {
                final DatagramDnsQuery dnsQuery = (DatagramDnsQuery) msg;
                final DnsRecord dnsRecord = dnsQuery.recordAt(DnsSection.QUESTION, 0);
                records.add(ByteArrayDnsRecord.copyOf(ReferenceCountUtil.retain(dnsRecord)));
            }
            super.channelRead(ctx, msg);
        }
    }

    @Test
    void resolve() throws Exception {
        final DnsRecordCaptor dnsRecordCaptor = new DnsRecordCaptor();
        try (TestDnsServer dnsServer = new TestDnsServer(ImmutableMap.of(
                new DefaultDnsQuestion("foo.com.", A),
                new DefaultDnsResponse(0).addRecord(ANSWER, newAddressRecord("foo.com.", "127.0.0.1"))), dnsRecordCaptor)) {
            try (ClientFactory factory = ClientFactory.builder()
                    .domainNameResolverCustomizer(b -> {
                        b.serverAddresses(dnsServer.addr());
                        b.searchDomains("search.domain1", "search.domain2");
                        b.ndots(3);
                    })
                    .build()) {

                final BlockingWebClient client =
                        WebClient.builder()
                                .factory(factory)
                                .build()
                                .blocking();
                final AggregatedHttpResponse response = client.get("http://foo.com.:" + server.httpPort() + '/');
                assertThat(response.contentUtf8()).isEqualTo("Hello, world!");
                assertThat(dnsRecordCaptor.records).hasSize(1);
                final DnsRecord record = dnsRecordCaptor.records.poll();
                assertThat(record.name()).isEqualTo("foo.com.");
            }
        }
    }
}
