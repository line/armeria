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
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.ClassRule;
import org.junit.Test;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class ProxyProtocolEnabledServerTest {

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

    @ClassRule
    public static final ServerRule server = new ServerRule() {
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
                    final InetSocketAddress dst = proxyAddresses.destinationAddress();
                    return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                           String.format("%s:%d -> %s:%d\n",
                                                         src.getHostString(), src.getPort(),
                                                         dst.getHostString(), dst.getPort()));
                }
            });
        }
    };

    @Test
    public void http() throws Exception {
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
    public void https() throws Exception {
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
    public void builder() throws Exception {
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
}
