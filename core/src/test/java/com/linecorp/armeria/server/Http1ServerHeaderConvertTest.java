/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.toArmeria;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.net.URISyntaxException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestTarget;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

class Http1ServerHeaderConvertTest {

    @Test
    void addHostHeaderIfMissing() throws URISyntaxException {
        final NettyHttp1Request originReq =
                new NettyHttp1Request(HttpVersion.HTTP_1_1, HttpMethod.GET, "/hello");
        final NettyHttp1Headers headers = originReq.headers();
        headers.add(HttpHeaderNames.HOST, "bar");

        final ChannelHandlerContext ctx = mockChannelHandlerContext();

        RequestHeaders armeriaHeaders = toArmeria(ctx, originReq, headers.delegate(),
                                                  serverConfig(), "http",
                                                  RequestTarget.forServer(originReq.uri()));
        assertThat(armeriaHeaders.get(HttpHeaderNames.HOST)).isEqualTo("bar");
        assertThat(armeriaHeaders.authority()).isEqualTo("bar");
        assertThat(armeriaHeaders.scheme()).isEqualTo("http");
        assertThat(armeriaHeaders.path()).isEqualTo("/hello");

        // Remove Host header.
        headers.remove(HttpHeaderNames.HOST);
        armeriaHeaders = toArmeria(ctx, originReq, headers.delegate(), serverConfig(), "https",
                                   RequestTarget.forServer(originReq.uri()));
        assertThat(armeriaHeaders.get(HttpHeaderNames.HOST)).isEqualTo("foo:36462"); // The default hostname.
        assertThat(armeriaHeaders.authority()).isEqualTo("foo:36462");
        assertThat(armeriaHeaders.scheme()).isEqualTo("https");
        assertThat(armeriaHeaders.path()).isEqualTo("/hello");
    }

    private static ServerConfig serverConfig() {
        final Server server = Server.builder()
                                    .defaultHostname("foo")
                                    .service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK))
                                    .build();
        return server.config();
    }

    private static ChannelHandlerContext mockChannelHandlerContext() {
        final InetSocketAddress socketAddress = new InetSocketAddress(36462);
        final Channel channel = mock(Channel.class);
        when(channel.localAddress()).thenReturn(socketAddress);

        final ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.channel()).thenReturn(channel);
        return ctx;
    }
}
