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

package com.linecorp.armeria.server.throttling.tokenbucket;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

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
import com.linecorp.armeria.server.throttling.RetryThrottlingService;
import com.linecorp.armeria.testing.junit4.server.ServerRule;

public class TokenBucketThrottlingStrategyTest {

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
            final TokenBucketConfig config = TokenBucketConfig.builder()
                    .limit(1L, Duration.ofSeconds(10L))
                    .retryAfterTimeout(Duration.ofSeconds(10L))
                    .build();
            sb.service("/http-serve",
                       SERVICE.decorate(
                               RetryThrottlingService
                                       .newDecorator(new TokenBucketThrottlingStrategy<>(config))));
            sb.service("/http-throttle",
                       SERVICE.decorate(
                               RetryThrottlingService
                                       .newDecorator(new TokenBucketThrottlingStrategy<>(config))));
        }
    };

    @Test
    public void serve() throws Exception {
        final WebClient client = WebClient.of(serverRule.uri("/"));
        assertThat(client.get("/http-serve").aggregate().get().status()).isEqualTo(HttpStatus.OK);
    }

    @Test
    public void throttle() throws Exception {
        final WebClient client = WebClient.of(serverRule.uri("/"));
        assertThat(client.get("/http-throttle").aggregate().get().status()).isEqualTo(HttpStatus.OK);
        final AggregatedHttpResponse response = client.get("/http-throttle").aggregate().get();
        assertThat(response.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.headers().contains(HttpHeaderNames.RETRY_AFTER, "10")).isTrue();
    }
}
