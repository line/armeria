/*
 * Copyright 2021 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerTooLargeContentTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsSelfSigned();
            sb.https(0);
            sb.http(0);
            sb.service("/", (ctx, req) -> {
                ctx.setMaxRequestLength(5);

                final HttpResponseWriter streaming = HttpResponse.streaming();
                streaming.write(ResponseHeaders.of(HttpStatus.OK, HttpHeaderNames.CONTENT_LENGTH, 7));
                req.aggregate().handle((aggregatedHttpRequest, cause) -> {
                    if (cause != null) {
                        streaming.close(cause);
                    } else {
                        streaming.write(HttpData.ofUtf8("content"));
                        streaming.close();
                    }
                    return null;
                });
                return streaming;
            });
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H1", "H1C" })
    void closedSessionForHttp1_contentTooLargeAfterResponseHeadersSent(SessionProtocol sessionProtocol) {
        final WebClient client = WebClient.builder(server.uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .build();
        assertThatThrownBy(() -> client.post("/", "abcdefgh").aggregate().join()).hasCauseInstanceOf(
                ClosedSessionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H2", "H2C" })
    void closedStreamForHttp2_contentTooLargeAfterResponseHeadersSent(SessionProtocol sessionProtocol) {
        final WebClient client = WebClient.builder(server.uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .build();
        assertThatThrownBy(() -> client.post("/", "abcdefgh").aggregate().join()).hasCauseInstanceOf(
                ClosedStreamException.class);
    }
}
