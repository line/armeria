/*
 *  Copyright 2022 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.handler.codec.http2.Http2Exception;

class Http2HeadersLimitTest {
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> HttpResponse.of("OK"));
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void shouldThrowHeaderListSizeException() throws InterruptedException {
        final BlockingWebClient client =
                server.blockingWebClient(cb -> cb.decorator(LoggingClient.newDecorator()));
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> {
                client.get("/?a=" + Strings.repeat("1", (int) Flags.defaultHttp2MaxHeaderListSize() + 1));
            }).isInstanceOf(UnprocessedRequestException.class)
              .hasCauseInstanceOf(Http2Exception.HeaderListSizeException.class)
              .hasMessageContaining("Header size exceeded max allowed size");
            final RequestLog log = captor.get().log().whenComplete().join();

            assertThat(log.responseCause())
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasCauseInstanceOf(Http2Exception.HeaderListSizeException.class)
                    .hasMessageContaining("Header size exceeded max allowed size");
        }
    }
}
