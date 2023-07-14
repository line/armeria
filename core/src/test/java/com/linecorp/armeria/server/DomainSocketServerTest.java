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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.DomainSocketAddress;
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

    static {
        System.err.println("## [TRACE] Initializing DomainSocketServerTest ...");
    }

    @RegisterExtension
    static final TemporaryFolderExtension tempDir = new TemporaryFolderExtension();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http(domainSocketAddress());
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }

        @Override
        public void before(ExtensionContext context) throws Exception {
            System.err.println("## [TRACE] Staring Unix domain socket server...");
            super.before(context);
            System.err.println("## [TRACE] Unix domain socket server has been started.");
        }

        @Override
        public void after(ExtensionContext context) throws Exception {
            System.err.println("## [TRACE] Stopping Unix domain socket server...");
            super.after(context);
            System.err.println("## [TRACE] Unix domain socket server has been stopped.");
        }
    };

    /**
     * Sends an HTTP/1 request via Unix domain socket using Netty and ensures the server responds with
     * a valid HTTP/1 response.
     */
    @Test
    void shouldSupportBindingOnDomainSocket() throws InterruptedException {
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
        System.err.println("## [TRACE] Connecting to " + domainSocketAddress() + " ...");
        final Channel ch = b.connect(domainSocketAddress().asNettyAddress())
                            .syncUninterruptibly()
                            .channel();
        System.err.println("## [TRACE] Connected to " + domainSocketAddress());

        System.err.println("## [TRACE] Sending an GET request to " + domainSocketAddress() + " ...");
        ch.writeAndFlush(Unpooled.copiedBuffer(
                  "GET / HTTP/1.1\r\n" +
                  "Connection: close\r\n" +
                  "\r\n", StandardCharsets.US_ASCII))
          .syncUninterruptibly();
        System.err.println("## [TRACE] Sent an GET request to " + domainSocketAddress());

        System.err.println("## [TRACE] Closing " + ch + " ...");
        ch.closeFuture().syncUninterruptibly();
        System.err.println("## [TRACE] Channel has been closed.");

        final String res = receivedBuffers.stream()
                                          .map(buf -> {
                                              final String str = buf.toString(StandardCharsets.US_ASCII);
                                              buf.release();
                                              return str;
                                          })
                                          .collect(Collectors.joining());

        assertThat(res).startsWith("HTTP/1.1 200 OK\r\n")
                       .contains("\r\nconnection: close\r\n")
                       .endsWith("\r\n\r\n200 OK");
    }

    private static DomainSocketAddress domainSocketAddress() {
        return DomainSocketAddress.of(tempDir.getRoot().resolve("test.sock"));
    }
}
