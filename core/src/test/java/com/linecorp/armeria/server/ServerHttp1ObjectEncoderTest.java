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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServerHttp1ObjectEncoderTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_MODIFIED)));
        }
    };

    @Test
    void chunkedEncodingIsNotSentWhen304Status() throws IOException {
        // Use raw socket instead of WebClient
        // because HttpUtil.setTransferEncodingChunked() remove the TRANSFER_ENCODING header.
        try (Socket socket = new Socket()) {
            socket.connect(server.httpSocketAddress());

            final PrintWriter outWriter = new PrintWriter(socket.getOutputStream(), false);
            outWriter.print("GET / HTTP/1.1\r\n");
            outWriter.print("\r\n");
            outWriter.flush();

            final InputStream is = socket.getInputStream();
            final BufferedReader reader = new BufferedReader(new InputStreamReader(is));

            final String transferEncoding = HttpHeaderNames.TRANSFER_ENCODING.toString().toLowerCase();
            for (;;) {
                final String line = reader.readLine();
                if (Strings.isNullOrEmpty(line)) {
                    break;
                }
                assertThat(line.toLowerCase()).doesNotContain(transferEncoding);
            }
        }
    }
}
