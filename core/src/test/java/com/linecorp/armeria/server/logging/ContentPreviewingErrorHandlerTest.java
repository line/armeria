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

package com.linecorp.armeria.server.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewingErrorHandlerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/ofFailure",
                       (ctx, req) -> HttpResponse.from(req.aggregate()
                                                          .thenApply(aReq -> HttpResponse.ofFailure(
                                                                  new IllegalStateException("Oops!"))),
                                                       ctx.eventLoop()));
            sb.service("/throw",
                       (ctx, req) -> HttpResponse.from(req.aggregate()
                                                          .thenApply(aReq -> {
                                                              throw new IllegalStateException("Oops!");
                                                          }),
                                                       ctx.eventLoop()));
            sb.errorHandler((ctx, cause) -> {
                final ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.INTERNAL_SERVER_ERROR)
                                                               .contentType(MediaType.PLAIN_TEXT)
                                                               .build();
                return HttpResponse.of(headers, HttpData.ofUtf8(cause.getMessage()));
            });
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(ContentPreviewingService.newDecorator(1024));
        }
    };

    @Test
    void responseContentPreviewOfFailure() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/ofFailure")
                                                     .contentType(MediaType.PLAIN_TEXT)
                                                     .build();
        final AggregatedHttpResponse res = server.blockingWebClient().execute(headers, "Armeria");
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8()).isEqualTo("Oops!");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestContentPreview()).isEqualTo("Armeria");
        assertThat(log.responseContentPreview()).isEqualTo("Oops!");
    }

    @Test
    void responseContentPreviewOfThrown() throws Exception {
        final RequestHeaders headers = RequestHeaders.builder(HttpMethod.POST, "/throw")
                                                     .contentType(MediaType.PLAIN_TEXT)
                                                     .build();
        final AggregatedHttpResponse res = server.blockingWebClient().execute(headers, "Armeria");
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8()).isEqualTo("Oops!");

        final ServiceRequestContext ctx = server.requestContextCaptor().take();
        final RequestLog log = ctx.log().whenComplete().join();
        assertThat(log.requestContentPreview()).isEqualTo("Armeria");
        assertThat(log.responseContentPreview()).isEqualTo("Oops!");
    }
}
