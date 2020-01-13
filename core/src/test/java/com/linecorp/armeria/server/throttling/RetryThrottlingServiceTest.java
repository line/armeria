/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.throttling;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionStage;

import javax.annotation.Nullable;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class RetryThrottlingServiceTest {

    static final HttpService SERVICE = new AbstractHttpService() {
        @Override
        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req)
                throws Exception {
            return HttpResponse.of(HttpStatus.OK);
        }
    };

    @Rule
    public ServerRule serverRule = new ServerRule() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/http-never",
                       SERVICE.decorate(
                               RetryThrottlingService.newDecorator(new TestRetryThrottlingNeverStrategy())));
            sb.service("/http-always",
                       SERVICE.decorate(
                               RetryThrottlingService.newDecorator(new TestRetryThrottlingAlwaysStrategy())));
        }
    };

    @Test
    public void serve() throws Exception {
        final WebClient client = WebClient.of(serverRule.uri("/"));
        assertThat(client.get("/http-always").aggregate().get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    public void throttle() throws Exception {
        final WebClient client = WebClient.of(serverRule.uri("/"));
        final AggregatedHttpResponse response = client.get("/http-never").aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.headers().contains(HttpHeaderNames.RETRY_AFTER, "10")).isTrue();
    }

    private static class TestRetryThrottlingAlwaysStrategy extends RetryThrottlingStrategy<HttpRequest> {
        @Nullable
        @Override
        protected String retryAfterSeconds() {
            return "10";
        }

        @Override
        public CompletionStage<Boolean> accept(ServiceRequestContext ctx, HttpRequest request) {
            return completedFuture(true);
        }
    }

    private static class TestRetryThrottlingNeverStrategy extends RetryThrottlingStrategy<HttpRequest> {
        @Nullable
        @Override
        protected String retryAfterSeconds() {
            return "10";
        }

        @Override
        public CompletionStage<Boolean> accept(ServiceRequestContext ctx, HttpRequest request) {
            return completedFuture(false);
        }
    }
}
