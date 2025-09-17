/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContentPreviewingClientLogCompletionTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/", (ctx, req) -> {
                return HttpResponse.of(HttpStatus.OK);
            });
        }
    };

    @ValueSource(ints = { 200, 400, 500 })
    @ParameterizedTest
    void testNormalResponse(int statusCode) {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             return HttpResponse.of(statusCode);
                         })
                         .decorator(RetryingClient.builder(RetryRule.failsafe())
                                                  .maxTotalAttempts(3)
                                                  .newDecorator())
                         .decorator(ContentPreviewingClient.newDecorator(100))
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse response = client.get("/");
            assertThat(response.status().code()).isEqualTo(statusCode);
            final ClientRequestContext ctx = captor.get();
            final RequestLog parent = ctx.log().whenComplete().join();
            for (RequestLogAccess child : parent.children()) {
                // Make sure that all child logs are complete.
                child.whenComplete().join();
            }
        }
    }

    @ValueSource(booleans = { true, false })
    @ParameterizedTest
    void testException(boolean throwException) {
        final BlockingWebClient client =
                WebClient.builder(server.httpUri())
                         .decorator((delegate, ctx, req) -> {
                             if (throwException) {
                                 throw new AnticipatedException();
                             } else {
                                 return HttpResponse.ofFailure(new AnticipatedException());
                             }
                         })
                         .decorator(RetryingClient.builder(RetryRule.failsafe())
                                                  .maxTotalAttempts(3)
                                                  .newDecorator())
                         .decorator(ContentPreviewingClient.newDecorator(100))
                         .build()
                         .blocking();
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            assertThatThrownBy(() -> client.get("/")).isInstanceOf(AnticipatedException.class);
            final ClientRequestContext ctx = captor.get();
            final RequestLog parent = ctx.log().whenComplete().join();
            for (RequestLogAccess child : parent.children()) {
                // Make sure that all child logs are complete.
                child.whenComplete().join();
            }
        }
    }
}
