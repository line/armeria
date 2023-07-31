/*
 * Copyright 2023 LINE Corporation
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class AbsoluteRequestUriTest {

    private static final HttpService service = (ctx, req) -> {
        return HttpResponse.of(firstNonNull(ctx.queryParam("uri"), "<null>"));
    };

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.absoluteUriTransformer(absoluteUri -> {
                try {
                    return "/proxy?uri=" + URLEncoder.encode(absoluteUri, "ISO-8859-1");
                } catch (UnsupportedEncodingException e) {
                    return Exceptions.throwUnsafely(e);
                }
            });

            sb.service("/proxy", service);
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithExceptionThrowingTransformer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.absoluteUriTransformer(absoluteUri -> {
                throw new RuntimeException("oops!");
            });

            sb.service("/proxy", service);
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithNullReturningTransformer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.absoluteUriTransformer(absoluteUri -> null);
            sb.service("/proxy", service);
        }
    };

    @RegisterExtension
    static final ServerExtension serverWithEmptyStringReturningTransformer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.absoluteUriTransformer(absoluteUri -> "");
            sb.service("/proxy", service);
        }
    };

    @Test
    void absoluteUriTransformation() throws Exception {
        assertThat(sendRequest(server))
                .startsWith("HTTP/1.1 200 OK\r\n")
                .endsWith("\r\nhttps://foo.com/bar");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badServers")
    void illegalAbsoluteUriTransformer(String serverName, ServerExtension server) throws Exception {
        assertThat(sendRequest(server))
                .startsWith("HTTP/1.1 400 Bad Request\r\n");
    }

    static List<Object[]> badServers() {
        return ImmutableList.of(
                new Object[] { "exceptionThrowing", serverWithExceptionThrowingTransformer },
                new Object[] { "nullReturning", serverWithNullReturningTransformer },
                new Object[] { "emptyStringReturning", serverWithEmptyStringReturningTransformer });
    }

    private static String sendRequest(ServerExtension server) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(server.httpSocketAddress());
            s.getOutputStream().write(
                    ("GET https://foo.com/bar HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Connection: close\r\n" +
                     "\r\n").getBytes(StandardCharsets.US_ASCII));
            return new String(ByteStreams.toByteArray(s.getInputStream()),
                              StandardCharsets.US_ASCII);
        }
    }
}
