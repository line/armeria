/*
 * Copyright 2023 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.DomainSocketAddress;
import com.linecorp.armeria.common.util.SystemInfo;
import com.linecorp.armeria.common.util.TransportType;
import com.linecorp.armeria.internal.testing.EnabledOnOsWithDomainSockets;
import com.linecorp.armeria.internal.testing.TemporaryFolderExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

@EnabledOnOsWithDomainSockets
class DomainSocketServerTest {

    private static final String ABSTRACT_PATH =
            '\0' + DomainSocketServerTest.class.getSimpleName() + '-' +
            ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            if (SystemInfo.isLinux()) {
                sb.http(domainSocketAddress(true));
            }
            sb.http(domainSocketAddress(false));
            sb.service("/",
                       (ctx, req) -> HttpResponse.builder()
                                                 .ok()
                                                 .content("OK")
                                                 .build());
            sb.service("/local-addr",
                       (ctx, req) -> HttpResponse.builder()
                                                 .ok()
                                                 .content(ctx.localAddress().toString())
                                                 .build());
            sb.service("/remote-addr",
                       (ctx, req) -> HttpResponse.builder()
                                                 .ok()
                                                 .content(ctx.remoteAddress().toString())
                                                 .build());
        }
    };

    /**
     * Sends an HTTP/1 request via Unix domain socket using Netty and ensures the server responds with
     * a valid HTTP/1 response.
     */
    @ParameterizedTest
    @CsvSource({ "false", "true" })
    void shouldSupportBindingOnDomainSocket(boolean useAbstractNamespace) {

        if (useAbstractNamespace && !SystemInfo.isLinux()) {
            // Abstract namespace is not supported on macOS.
            return;
        }

        final String res = get("/", useAbstractNamespace);
        assertThat(res).startsWith("HTTP/1.1 200 OK\r\n")
                       .contains("\r\nconnection: close\r\n")
                       .endsWith("\r\n\r\nOK");
    }

    /**
     * Ensures {@link ServiceRequestContext#localAddress()} and {@link ServiceRequestContext#remoteAddress()}
     * return the correct address.
     */
    @ParameterizedTest
    @CsvSource({
            "/local-addr,  false",
            "/local-addr,  true",
            "/remote-addr, false",
            "/remote-addr, true"
    })
    void contextShouldReturnCorrectAddress(String requestPath, boolean useAbstractNamespace) {
        if (useAbstractNamespace && !SystemInfo.isLinux()) {
            // Abstract namespace is not supported on macOS.
            return;
        }

        final String res = get(requestPath, useAbstractNamespace);
        assertThat(res).startsWith("HTTP/1.1 200 OK\r\n")
                       .contains("\r\nconnection: close\r\n")
                       .endsWith("\r\n\r\n" + domainSocketAddress(useAbstractNamespace));
    }

    private static String get(String requestPath, boolean useAbstractNamespace) {
        final BlockingQueue<ByteBuf> receivedBuffers = new LinkedTransferQueue<>();
        final TransportType transportType = Flags.transportType();
        final Bootstrap b = new Bootstrap();
        b.group(CommonPools.workerGroup());
        b.channel(transportType.domainSocketChannelType());
        b.handler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                receivedBuffers.add((ByteBuf) msg);
            }
        });
        final Channel ch = b.connect(domainSocketAddress(useAbstractNamespace).asNettyAddress())
                            .syncUninterruptibly()
                            .channel();

        ch.writeAndFlush(Unpooled.copiedBuffer(
                  "GET " + requestPath + " HTTP/1.1\r\n" +
                  "Connection: close\r\n" +
                  "\r\n", StandardCharsets.US_ASCII))
          .syncUninterruptibly();

        ch.closeFuture().syncUninterruptibly();

        return receivedBuffers.stream()
                              .map(buf -> {
                                  final String str = buf.toString(StandardCharsets.US_ASCII);
                                  buf.release();
                                  return str;
                              })
                              .collect(Collectors.joining());
    }

    private static DomainSocketAddress domainSocketAddress(boolean useAbstractNamespace) {
        return DomainSocketAddress.of(
                useAbstractNamespace ? ABSTRACT_PATH : tempDir.getRoot().resolve("test.sock").toString());
    }
}
