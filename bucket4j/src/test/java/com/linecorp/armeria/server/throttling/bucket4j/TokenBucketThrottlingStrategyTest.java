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

package com.linecorp.armeria.server.throttling.bucket4j;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.throttling.ThrottlingHeaders;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.throttling.ThrottlingService;
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
            final TokenBucket tokenBucket = TokenBucket.builder()
                                                       .limit(1L, Duration.ofSeconds(10L))
                                                       .build();
            sb.service("/http-serve",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .build())));
            sb.service("/http-throttle1",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .headersScheme(ThrottlingHeaders.X_RATELIMIT)
                                               .build())));
            sb.service("/http-throttle2",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .minimumBackoff(Duration.ofSeconds(15L))
                                               .headersScheme(ThrottlingHeaders.X_RATELIMIT, true)
                                               .build())));
            sb.service("/http-throttle3",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .build())));
            sb.service("/http-throttle4",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .minimumBackoff(Duration.ofSeconds(5L))
                                               .build(),
                                       (delegate, ctx, req, cause) ->
                                               HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE))));
        }
    };

    @Test
    public void serve1() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response = client.get("/http-serve");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        assertThat(response.headers().contains(HttpHeaderNames.RETRY_AFTER)).isFalse();
        assertThat(response.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response.headers().contains("X-RateLimit-Remaining")).isFalse();
        assertThat(response.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response.headers().contains("RateLimit-Reset")).isFalse();
        assertThat(response.headers().contains("X-RateLimit-Reset")).isFalse();
        assertThat(response.headers().contains("X-Rate-Limit-Reset")).isFalse();
        assertThat(response.headers().contains("RateLimit-Limit")).isFalse();
        assertThat(response.headers().contains("X-RateLimit-Limit")).isFalse();
        assertThat(response.headers().contains("X-Rate-Limit-Limit")).isFalse();
    }

    @Test
    public void throttle1() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle1");
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);

        assertThat(response1.headers().contains(HttpHeaderNames.RETRY_AFTER)).isFalse();
        assertThat(response1.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Remaining", "0")).isTrue();
        assertThat(response1.headers().contains("X-RateLimit-Reset")).isTrue();
        final long reset1 = Long.parseLong(response1.headers().get("X-RateLimit-Reset"));
        assertThat(reset1).isBetween(0L, 10L);
        assertThat(response1.headers().contains("X-RateLimit-Limit")).isFalse();

        final AggregatedHttpResponse response2 = client.get("/http-throttle1");
        assertThat(response2.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(response2.headers().contains(HttpHeaderNames.RETRY_AFTER)).isTrue();
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertThat(retryAfter2).isBetween(0L, 10L);
        assertThat(response2.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Remaining", "0")).isTrue();
        assertThat(response2.headers().contains("X-RateLimit-Reset")).isTrue();
        final long reset = Long.parseLong(response2.headers().get("X-RateLimit-Reset"));
        assertThat(reset).isEqualTo(retryAfter2);
        assertThat(response2.headers().contains("X-RateLimit-Limit")).isFalse();
    }

    @Test
    public void throttle2() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle2");
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);

        assertThat(response1.headers().contains(HttpHeaderNames.RETRY_AFTER)).isFalse();
        assertThat(response1.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Remaining", "0")).isTrue();
        assertThat(response1.headers().contains("X-RateLimit-Reset")).isTrue();
        final long reset1 = Long.parseLong(response1.headers().get("X-RateLimit-Reset"));
        assertThat(reset1).isBetween(0L, 10L);
        assertThat(response1.headers().get("X-RateLimit-Limit")).isEqualTo("1, 1;window=10");

        final AggregatedHttpResponse response2 = client.get("/http-throttle2");
        assertThat(response2.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(response2.headers().contains(HttpHeaderNames.RETRY_AFTER, "15")).isTrue();
        assertThat(response2.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Remaining", "0")).isTrue();
        assertThat(response2.headers().contains("X-RateLimit-Reset", "15")).isTrue();
        assertThat(response1.headers().get("X-RateLimit-Limit")).isEqualTo("1, 1;window=10");
    }

    @Test
    public void throttle3() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle3");
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);

        assertThat(response1.headers().contains(HttpHeaderNames.RETRY_AFTER)).isFalse();
        assertThat(response1.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Reset")).isFalse();

        final AggregatedHttpResponse response2 = client.get("/http-throttle3");
        assertThat(response2.status()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(response2.headers().contains(HttpHeaderNames.RETRY_AFTER)).isTrue();
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertThat(retryAfter2).isBetween(0L, 10L);
        assertThat(response2.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Reset")).isFalse();
    }

    @Test
    public void throttle4() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle4");
        assertThat(response1.status()).isEqualTo(HttpStatus.OK);

        assertThat(response1.headers().contains(HttpHeaderNames.RETRY_AFTER)).isFalse();
        assertThat(response1.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Remaining")).isFalse();
        assertThat(response1.headers().contains("X-RateLimit-Reset")).isFalse();

        final AggregatedHttpResponse response2 = client.get("/http-throttle4");
        assertThat(response2.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        assertThat(response2.headers().contains(HttpHeaderNames.RETRY_AFTER)).isTrue();
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertThat(retryAfter2).isBetween(5L, 10L);
        assertThat(response2.headers().contains("RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-Rate-Limit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Remaining")).isFalse();
        assertThat(response2.headers().contains("X-RateLimit-Reset")).isFalse();
    }
}
