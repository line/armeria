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

package com.linecorp.armeria.server;

import static com.linecorp.armeria.common.SessionProtocol.HTTP;
import static com.linecorp.armeria.common.SessionProtocol.HTTPS;
import static com.linecorp.armeria.common.SessionProtocol.PROXY;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

class ProxyProtocolEnabledServerTest {

    private static final TrustManager[] trustAllCerts = {
            new X509TrustManager() {
                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }

                @Override
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}

                @Override
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
    };

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.port(0, PROXY, HTTP);
            sb.port(0, PROXY, HTTPS);
            sb.tlsSelfSigned();
            sb.service("/", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    final ProxiedAddresses proxyAddresses = ctx.proxiedAddresses();
                    assert proxyAddresses != null;
                    final InetSocketAddress src = proxyAddresses.sourceAddress();
                    final List<InetSocketAddress> dst = proxyAddresses.destinationAddresses();
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                           String.format("%s:%d -> %s:%d\n",
                                                         src.getHostString(), src.getPort(),
                                                         dst.get(0).getHostString(), dst.get(0).getPort()));
                }
            });

            sb.service("/null-proxyaddr", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    assert ctx.proxiedAddresses().destinationAddresses().isEmpty();
                    return HttpResponse.of(HttpStatus.OK);
                }
            });
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @Test
    void http() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", server.httpPort())) {
            final PrintWriter writer = new PrintWriter(sock.getOutputStream());
            writer.print("PROXY TCP4 192.168.0.1 192.168.0.11 56324 443\r\n");
            writer.print("GET / HTTP/1.1\r\n\r\n");
            writer.flush();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            checkResponse(reader);

            writer.print("GET / HTTP/1.1\r\n\r\n");
            writer.flush();

            checkResponse(reader);
        }
    }

    @Test
    void shouldAcceptUnknownProtocol() throws IOException {
        final HAProxyMessage haProxyMessage = new HAProxyMessage(
                HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.UNKNOWN,
                null, null, 0, 0);
        final byte[] encoded = encodeV2UnknownProtocol(haProxyMessage);

        try (Socket sock = new Socket("127.0.0.1", server.httpPort())) {
            final OutputStream os = sock.getOutputStream();
            os.write(encoded);
            os.write("GET /null-proxyaddr HTTP/1.1\r\n\r\n".getBytes());
            os.flush();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            assertThat(reader.readLine()).isEqualToIgnoringCase("HTTP/1.1 200 OK");
        } finally {
           haProxyMessage.release();
        }
    }

    @Test
    void https() throws Exception {
        try (Socket sock = new Socket("127.0.0.1", server.httpsPort())) {
            final PrintWriter writer = new PrintWriter(sock.getOutputStream());
            writer.print("PROXY TCP4 192.168.0.1 192.168.0.11 56324 443\r\n");
            writer.flush();

            final SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            final Socket sslSock =
                    sc.getSocketFactory().createSocket(sock, "127.0.0.1", server.httpsPort(), false);
            final PrintWriter sslWriter = new PrintWriter(sslSock.getOutputStream());
            sslWriter.print("GET / HTTP/1.1\r\n\r\n");
            sslWriter.flush();

            final BufferedReader reader = new BufferedReader(new InputStreamReader(sslSock.getInputStream()));
            checkResponse(reader);

            sslWriter.print("GET / HTTP/1.1\r\n\r\n");
            sslWriter.flush();

            checkResponse(reader);
        }
    }

    @Test
    void builder() throws Exception {
        final Object service = new Object() {
            @Get("/")
            public String get() {
                return "";
            }
        };
        assertThat(Server.builder()
                         .tlsSelfSigned()
                         .port(0, PROXY, HTTP, HTTPS)
                         .annotatedService(service)
                         .build()).isNotNull();
        assertThat(Server.builder()
                         .tlsSelfSigned()
                         .port(0, PROXY, HTTPS)
                         .annotatedService(service)
                         .build()).isNotNull();
        assertThat(Server.builder()
                         .port(0, PROXY, HTTP)
                         .annotatedService(service)
                         .build()).isNotNull();
    }

    private static void checkResponse(BufferedReader reader) throws IOException {
        assertThat(reader.readLine()).isEqualToIgnoringCase("HTTP/1.1 200 OK");

        // Content-Length header, Content-Type header, an empty line
        reader.readLine();
        reader.readLine();
        reader.readLine();

        assertThat(reader.readLine()).isEqualToIgnoringCase("192.168.0.1:56324 -> 192.168.0.11:443");
    }

    private static byte[] encodeV2UnknownProtocol(HAProxyMessage msg) {
        final byte[] binaryPrefix = {
                (byte) 0x0D, (byte) 0x0A, (byte) 0x0D, (byte) 0x0A, (byte) 0x00, (byte) 0x0D,
                (byte) 0x0A, (byte) 0x51, (byte) 0x55, (byte) 0x49, (byte) 0x54, (byte) 0x0A
        };
        final int v2VersionBitmask = 0x02 << 4;

        final ByteBuf buf = Unpooled.buffer();
        buf.writeBytes(binaryPrefix);
        buf.writeByte(v2VersionBitmask | msg.command().byteValue());
        buf.writeByte(msg.proxiedProtocol().byteValue());
        buf.writeShort(0);

        final byte[] out = new byte[buf.readableBytes()];
        buf.writeBytes(out);
        buf.release();
        return out;
    }
}
