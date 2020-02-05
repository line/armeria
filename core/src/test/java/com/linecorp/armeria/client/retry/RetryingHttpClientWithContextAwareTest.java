/*
 * Copyright 2019 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

class RetryingHttpClientWithContextAwareTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @Test
    void contextAwareDoesNotThrowException() {
        final WebClient client =
                WebClient.builder(server.httpUri())
                         .responseTimeoutMillis(100)
                         .decorator(RetryingClient.builder(RetryStrategy.onServerErrorStatus())
                                                  .maxTotalAttempts(2)
                                                  .newDecorator())
                         .build();

        final ServiceRequestContext dummyCtx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        try (SafeCloseable ignored = dummyCtx.push()) {
            final CompletableFuture<AggregatedHttpResponse> future = client.get("/").aggregate();
            assertThatThrownBy(() -> dummyCtx.makeContextAware(future).join()).hasCauseInstanceOf(
                    ResponseTimeoutException.class);
        }
    }
}
