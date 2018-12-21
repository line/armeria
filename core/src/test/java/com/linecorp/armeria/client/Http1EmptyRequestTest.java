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
package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.testing.common.EventLoopRule;

/**
 * Makes sure an empty HTTP/1 request is sent with or without the {@code content-length} header
 * for all HTTP methods.
 */
@RunWith(Parameterized.class)
public class Http1EmptyRequestTest {

    @Parameters(name = "{index}: method={0}, hasContentLength={1}")
    public static Collection<Object[]> parameters() {
        return ImmutableList.of(new Object[] { HttpMethod.OPTIONS, false },
                                new Object[] { HttpMethod.GET, false },
                                new Object[] { HttpMethod.HEAD, false },
                                new Object[] { HttpMethod.POST, true },
                                new Object[] { HttpMethod.PUT, true },
                                new Object[] { HttpMethod.PATCH, true },
                                new Object[] { HttpMethod.DELETE, false },
                                new Object[] { HttpMethod.TRACE, false },
                                new Object[] { HttpMethod.CONNECT, false });
    }

    @ClassRule
    public static final EventLoopRule eventLoop = new EventLoopRule();

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    private final HttpMethod method;
    private final boolean hasContentLength;

    public Http1EmptyRequestTest(HttpMethod method, boolean hasContentLength) {
        this.method = method;
        this.hasContentLength = hasContentLength;
    }

    @Test
    public void emptyRequest() throws Exception {
        try (ServerSocket ss = new ServerSocket(0);) {
            final int port = ss.getLocalPort();

            final HttpClient client = HttpClient.of("h1c://127.0.0.1:" + port);
            client.execute(HttpRequest.of(method, "/")).aggregate();

            try (Socket s = ss.accept()) {
                final BufferedReader in = new BufferedReader(
                        new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                assertThat(in.readLine()).isEqualTo(method.name() + " / HTTP/1.1");
                assertThat(in.readLine()).startsWith("host: 127.0.0.1:");
                assertThat(in.readLine()).startsWith("user-agent: armeria/");
                if (hasContentLength) {
                    assertThat(in.readLine()).isEqualTo("content-length: 0");
                }
                assertThat(in.readLine()).isEmpty();
            }
        }
    }
}
