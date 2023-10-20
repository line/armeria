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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.logging.ContentPreviewingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.ContentPreviewingService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewInLogFormatterTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.decorator(ContentPreviewingService.newDecorator(ContentPreviewerFactory.text(10000)));
            sb.service("/foo", (ctx, req) -> {
                return HttpResponse.of(req.aggregate().thenApply(agg -> {
                    return HttpResponse.of("World");
                }));
            });
        }
    };

    @Test
    void shouldLogContentPreview() throws InterruptedException {
        final BlockingWebClient client = server.blockingWebClient(cb -> {
            cb.decorator(LoggingClient.newDecorator())
              .decorator(ContentPreviewingClient.newDecorator(10000));
        });

        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.prepare()
                  .post("/foo")
                  .content(MediaType.PLAIN_TEXT_UTF_8, "Hello")
                  .execute();
            final RequestLogAccess log = captor.get().log();
            assertContentPreview(log);
            assertContentPreview(server.requestContextCaptor().take().log());
        }
    }

    private static void assertContentPreview(RequestLogAccess logAccess) {
        final RequestLog log = logAccess.whenComplete().join();
        assertThat(log.requestContentPreview()).isEqualTo("Hello");
        assertThat(log.responseContentPreview()).isEqualTo("World");

        final LogFormatter textLogFormatter = LogFormatter.ofText();
        assertThat(textLogFormatter.formatRequest(log)).contains(", content=Hello");
        assertThat(textLogFormatter.formatResponse(log)).contains(", content=World");
        final LogFormatter jsonLogFormatter = LogFormatter.ofJson();
        assertThat(jsonLogFormatter.formatRequest(log)).contains("\"content\":\"Hello\"");
        assertThat(jsonLogFormatter.formatResponse(log)).contains("\"content\":\"World\"");
    }
}
