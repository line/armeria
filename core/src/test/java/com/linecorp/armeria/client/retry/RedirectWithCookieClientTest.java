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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.cookie.CookieClient;
import com.linecorp.armeria.client.cookie.CookiePolicy;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RedirectWithCookieClientTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {

            sb.service("/old", (ctx, req) -> {
                final ResponseHeaders headers = ResponseHeaders.builder()
                                                               .status(HttpStatus.SEE_OTHER)
                                                               .add(HttpHeaderNames.LOCATION, "/new")
                                                               .cookie(Cookie.builder("foo", "bar")
                                                                             .path("/")
                                                                             .build())
                                                               .build();
                return HttpResponse.of(headers);
            });
            sb.service("/new", (ctx, req) -> {
                final Cookie cookie = req.headers().cookies().stream().findFirst().get();
                return HttpResponse.of(cookie.name() + '=' + cookie.value());
            });
            sb.decorator(LoggingService.newDecorator());
        }
    };

    @Test
    void shouldWorkCookieClientWithRedirection() {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .followRedirects()
                         .decorator(LoggingClient.newDecorator())
                         .decorator(CookieClient.newDecorator(CookiePolicy.acceptAll()))
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThat(client.get("/old").contentUtf8()).isEqualTo("foo=bar");
            final ClientRequestContext context = captor.get();
            final RequestLog log = context.log().whenComplete().join();
            assertThat(log.children().size()).isEqualTo(2);
            for (RequestLogAccess childLog : log.children()) {
                // Should not raise an exception.
                assertThat(childLog.whenComplete().join().responseCause()).isNull();
            }
        }
    }
}
