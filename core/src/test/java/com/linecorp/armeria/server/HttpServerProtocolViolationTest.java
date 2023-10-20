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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerProtocolViolationTest {
    private static final int MAX_CONTENT_LENGTH = 10000;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.maxRequestLength(MAX_CONTENT_LENGTH);
            sb.decorator(LoggingService.newDecorator());
            sb.service("/echo", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(
                        agg -> HttpResponse.of(ResponseHeaders.of(200), agg.content())));
            });
        }
    };

    @CsvSource({"H1C", "H2C"})
    @ParameterizedTest
    void shouldRejectLargeRequest(SessionProtocol protocol) throws InterruptedException {
        final WebClient client = WebClient.of(server.uri(protocol));
        final byte[] bytes = new byte[MAX_CONTENT_LENGTH + 1];
        Arrays.fill(bytes, (byte) 1);

        final AggregatedHttpResponse response = client.post("/echo", bytes).aggregate().join();
        final ServiceRequestContext sctx = server.requestContextCaptor().take();
        final RequestLog responseLog = sctx.log().whenComplete().join();

        assertThat(response.status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
        assertThat(responseLog.responseHeaders().status()).isEqualTo(HttpStatus.REQUEST_ENTITY_TOO_LARGE);
    }
}
