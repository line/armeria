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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import joptsimple.internal.Strings;

class ProtocolViolationHandlingTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.http1MaxInitialLineLength(100);
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.errorHandler(new ServerErrorHandler() {
                @Override
                public @Nullable HttpResponse onServiceException(ServiceRequestContext ctx, Throwable cause) {
                    return null;
                }

                @Override
                public @Nullable AggregatedHttpResponse onProtocolViolation(ServiceConfig config,
                                                                            @Nullable RequestHeaders headers,
                                                                            HttpStatus status,
                                                                            @Nullable String description,
                                                                            @Nullable Throwable cause) {
                    return AggregatedHttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.PLAIN_TEXT,
                                                     "Custom response");
                }
            });
        }
    };

    @Test
    void shouldHandleInvalidHttp1Request() {
        final BlockingWebClient client = BlockingWebClient.of(server.uri(SessionProtocol.H1C));
        final AggregatedHttpResponse res = client.get("/?" + Strings.repeat('a', 100));
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).isEqualTo("Custom response");
    }

    @Test
    void shouldHandleInvalidHttp2Request() {
        final BlockingWebClient client = BlockingWebClient.of(server.uri(SessionProtocol.H2C));
        final AggregatedHttpResponse res = client.get("*");
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.contentUtf8()).isEqualTo("Custom response");
    }
}
