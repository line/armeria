/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;

import com.linecorp.armeria.common.HttpResponse;

public class HttpClientTimeoutTest {

    private static ClientFactory factory;

    @BeforeClass
    public static void init() {
        factory = ClientFactory.builder().useHttp2Preface(true).build();
    }

    @AfterClass
    public static void destroy() {
        factory.closeAsync();
    }

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Test
    public void responseTimeoutH1C() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            final WebClient client = WebClient.builder("h1c://127.0.0.1:" + ss.getLocalPort())
                                              .factory(factory)
                                              .responseTimeout(Duration.ofSeconds(1))
                                              .build();

            final HttpResponse res = client.get("/");
            try (Socket s = ss.accept()) {
                s.setSoTimeout(1000);

                // Let the response timeout occur.
                assertThatThrownBy(() -> res.aggregate().join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(ResponseTimeoutException.class);

                // Make sure that the connection is closed.
                final InputStream in = s.getInputStream();
                while (in.read() >= 0) {
                    continue;
                }
            }
        }
    }

    @Test
    public void responseTimeoutH2C() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            final WebClient client = WebClient.builder("h2c://127.0.0.1:" + ss.getLocalPort())
                                              .factory(factory)
                                              .responseTimeout(Duration.ofSeconds(1))
                                              .build();

            final HttpResponse res = client.get("/");
            try (Socket s = ss.accept()) {
                s.setSoTimeout(1000);

                final InputStream in = s.getInputStream();
                final OutputStream out = s.getOutputStream();

                // Wait for the client to send an H2C upgrade request.
                assertThat(in.read()).isGreaterThanOrEqualTo(0);

                // Send an empty SETTINGS frame.
                out.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00, 0x00, 0x00 });

                // Send a SETTINGS_ACK frame.
                out.write(new byte[] { 0x00, 0x00, 0x00, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00 });

                // Let the response timeout occur.
                assertThatThrownBy(() -> res.aggregate().join())
                        .isInstanceOf(CompletionException.class)
                        .hasCauseInstanceOf(ResponseTimeoutException.class);

                // Make sure that the client sent the RST_STREAM frame.
                final byte[] buf = new byte[8192];
                int bufLen = 0;
                try {
                    for (;;) {
                        final int numBytes = in.read(buf, bufLen, buf.length - bufLen);
                        assertThat(numBytes).isGreaterThanOrEqualTo(0);
                        bufLen += numBytes;
                    }
                } catch (SocketTimeoutException expected) {
                    // At this point, the 'buf' should contain the RST_STREAM frame at the end.
                }

                assertThat(Arrays.copyOfRange(buf, bufLen - 13, bufLen)).containsExactly(
                        0x00, 0x00, 0x04,        // Length = 4
                        0x03, 0x00,              // Type = 3 (RST_STREAM), Flag = 0
                        0x00, 0x00, 0x00, 0x03,  // Stream ID = 3
                        0x00, 0x00, 0x00, 0x08); // Error Code = 8 (CANCEL)
            }
        }
    }
}
