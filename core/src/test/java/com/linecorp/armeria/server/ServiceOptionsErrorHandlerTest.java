/*
 * Copyright 2026 LINE Corporation
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

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ServiceOptionsErrorHandlerTest {

    private static final class AnticipatedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /**
     * A service that fails every request and answers with {@code marker} through its own
     * {@link ServiceOptions}.
     */
    private static HttpService failingService(@Nullable String marker) {
        return new HttpService() {
            @Override
            public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                throw new AnticipatedException();
            }

            @Override
            public ServiceOptions options() {
                return ServiceOptions.builder()
                                     .errorHandler((ctx, cause) -> {
                                         if (marker == null || !(cause instanceof AnticipatedException)) {
                                             return null;
                                         }
                                         return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                                marker);
                                     })
                                     .build();
            }
        };
    }

    @RegisterExtension
    static ServerExtension serviceOnly = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", failingService("service"));
        }
    };

    @RegisterExtension
    static ServerExtension bindingWins = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.route()
              .path("/")
              .errorHandler((ctx, cause) -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                            "binding"))
              .build(failingService("service"));
        }
    };

    @RegisterExtension
    static ServerExtension fallsThrough = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.errorHandler((ctx, cause) -> HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8,
                                                            "server"));
            // The service declares a handler that always returns null.
            sb.service("/", failingService(null));
        }
    };

    @Test
    void serviceOptionsHandlerIsUsed() {
        final AggregatedHttpResponse res = WebClient.of(serviceOnly.httpUri()).blocking().get("/");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("service");
    }

    @Test
    void handlerOnBindingTakesPrecedenceOverServiceOptions() {
        final AggregatedHttpResponse res = WebClient.of(bindingWins.httpUri()).blocking().get("/");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("binding");
    }

    @Test
    void nullFromServiceOptionsHandlerFallsBackToServerHandler() {
        final AggregatedHttpResponse res = WebClient.of(fallsThrough.httpUri()).blocking().get("/");
        assertThat(res.status()).isSameAs(HttpStatus.OK);
        assertThat(res.contentUtf8()).isEqualTo("server");
    }
}
