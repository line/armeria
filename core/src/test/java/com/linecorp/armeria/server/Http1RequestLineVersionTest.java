/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class Http1RequestLineVersionTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "HTTP/1.0", "HTTP/1.1", "http/1.1" })
    void acceptsValidHttpVersion(String version) throws Exception {
        assertThat(statusLineOf(version)).startsWith("HTTP/1.1 200");
    }

    /**
     * RFC 9112 allows only {@code HTTP/<major>.<minor>} with a single digit each. Accepting anything
     * else lets a request line mean one thing to a proxy and another to this server.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // A non-HTTP protocol name.
            "RTSP/1.0", "ICAP/1.0", "BOGUS/9.9",
            // More than a single digit for the major or minor version.
            "HTTP/11.22", "HTTP/01.1", "HTTP/1.1.1", "HTTP/1",
            // A non-digit where the major or minor version belongs.
            "HTTP/a.b", "HTTP/1.b", "HTTP/a.1", "HTTP/-.1", "HTTP/1.-"
    })
    void rejectsNonHttpVersion(String version) throws Exception {
        assertThat(statusLineOf(version)).startsWith("HTTP/1.1 400");
    }

    private static String statusLineOf(String version) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", server.httpPort())) {
            socket.setSoTimeout(10000);
            final OutputStream out = socket.getOutputStream();
            out.write(("GET / " + version + "\r\nHost: foo\r\n\r\n")
                              .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();

            final InputStream in = socket.getInputStream();
            final byte[] buf = new byte[512];
            final int n = in.read(buf);
            assertThat(n).isPositive();
            return new String(buf, 0, n, StandardCharsets.ISO_8859_1).split("\r\n")[0];
        }
    }
}
