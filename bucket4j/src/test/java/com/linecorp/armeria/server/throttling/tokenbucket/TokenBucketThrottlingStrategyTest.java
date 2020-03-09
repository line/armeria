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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.Rule;
import org.junit.Test;

import com.linecorp.armeria.client.WebClient;
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
                                               .withHeadersScheme(ThrottlingHeaders.X_RATELIMIT)
                                               .build())));
            sb.service("/http-throttle2",
                       SERVICE.decorate(
                               ThrottlingService.newDecorator(
                                       TokenBucketThrottlingStrategy.<HttpRequest>builder(tokenBucket)
                                               .withMinimumBackoff(Duration.ofSeconds(15L))
                                               .withHeadersScheme(ThrottlingHeaders.X_RATELIMIT, true)
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
                                               .withMinimumBackoff(Duration.ofSeconds(5L))
                                               .build(),
                                       (delegate, ctx, req, cause) ->
                                               HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE))));
        }
    };

    @Test
    public void serve1() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response = client.get("/http-serve").aggregate().get();
        assertEquals(HttpStatus.OK, response.status());
        System.out.println(response.headers());

        assertFalse(response.headers().contains(HttpHeaderNames.RETRY_AFTER));
        assertFalse(response.headers().contains("RateLimit-Remaining"));
        assertFalse(response.headers().contains("X-RateLimit-Remaining"));
        assertFalse(response.headers().contains("X-Rate-Limit-Remaining"));
        assertFalse(response.headers().contains("RateLimit-Reset"));
        assertFalse(response.headers().contains("X-RateLimit-Reset"));
        assertFalse(response.headers().contains("X-Rate-Limit-Reset"));
        assertFalse(response.headers().contains("RateLimit-Limit"));
        assertFalse(response.headers().contains("X-RateLimit-Limit"));
        assertFalse(response.headers().contains("X-Rate-Limit-Limit"));
    }

    @Test
    public void throttle1() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle1").aggregate().get();
        assertEquals(HttpStatus.OK, response1.status());
        System.out.println(response1.headers());

        assertFalse(response1.headers().contains(HttpHeaderNames.RETRY_AFTER));
        assertFalse(response1.headers().contains("RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-Rate-Limit-Remaining"));
        assertTrue(response1.headers().contains("X-RateLimit-Remaining", "0"));
        assertTrue(response1.headers().contains("X-RateLimit-Reset"));
        final long reset1 = Long.parseLong(response1.headers().get("X-RateLimit-Reset"));
        assertTrue(reset1 <= 10L && reset1 >= 0L);
        assertFalse(response1.headers().contains("X-RateLimit-Limit"));

        final AggregatedHttpResponse response2 = client.get("/http-throttle1").aggregate().get();
        System.out.println(response2.headers());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response2.status());

        assertTrue(response2.headers().contains(HttpHeaderNames.RETRY_AFTER));
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertTrue(retryAfter2 <= 10L && retryAfter2 >= 0L);
        assertFalse(response2.headers().contains("RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-Rate-Limit-Remaining"));
        assertTrue(response2.headers().contains("X-RateLimit-Remaining", "0"));
        assertTrue(response2.headers().contains("X-RateLimit-Reset"));
        final long reset = Long.parseLong(response2.headers().get("X-RateLimit-Reset"));
        assertEquals(retryAfter2, reset);
        assertFalse(response2.headers().contains("X-RateLimit-Limit"));
    }

    @Test
    public void throttle2() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle2").aggregate().get();
        assertEquals(HttpStatus.OK, response1.status());
        System.out.println(response1.headers());

        assertFalse(response1.headers().contains(HttpHeaderNames.RETRY_AFTER));
        assertFalse(response1.headers().contains("RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-Rate-Limit-Remaining"));
        assertTrue(response1.headers().contains("X-RateLimit-Remaining", "0"));
        assertTrue(response1.headers().contains("X-RateLimit-Reset"));
        final long reset1 = Long.parseLong(response1.headers().get("X-RateLimit-Reset"));
        assertTrue(reset1 <= 10L && reset1 >= 0L);
        assertEquals("1, 1;window=10", response1.headers().get("X-RateLimit-Limit"));

        final AggregatedHttpResponse response2 = client.get("/http-throttle2").aggregate().get();
        System.out.println(response2.headers());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response2.status());

        assertTrue(response2.headers().contains(HttpHeaderNames.RETRY_AFTER, "15"));
        assertFalse(response2.headers().contains("RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-Rate-Limit-Remaining"));
        assertTrue(response2.headers().contains("X-RateLimit-Remaining", "0"));
        assertTrue(response2.headers().contains("X-RateLimit-Reset", "15"));
        assertEquals("1, 1;window=10", response1.headers().get("X-RateLimit-Limit"));
    }

    @Test
    public void throttle3() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle3").aggregate().get();
        assertEquals(HttpStatus.OK, response1.status());
        System.out.println(response1.headers());

        assertFalse(response1.headers().contains(HttpHeaderNames.RETRY_AFTER));
        assertFalse(response1.headers().contains("RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-Rate-Limit-Remaining"));
        assertFalse(response1.headers().contains("X-RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-RateLimit-Reset"));

        final AggregatedHttpResponse response2 = client.get("/http-throttle3").aggregate().get();
        System.out.println(response2.headers());
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response2.status());

        assertTrue(response2.headers().contains(HttpHeaderNames.RETRY_AFTER));
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertTrue(retryAfter2 <= 10L && retryAfter2 >= 0L);
        assertFalse(response2.headers().contains("RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-Rate-Limit-Remaining"));
        assertFalse(response2.headers().contains("X-RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-RateLimit-Reset"));
    }

    @Test
    public void throttle4() throws Exception {
        final WebClient client = WebClient.of(serverRule.httpUri());
        final AggregatedHttpResponse response1 = client.get("/http-throttle4").aggregate().get();
        assertEquals(HttpStatus.OK, response1.status());
        System.out.println(response1.headers());

        assertFalse(response1.headers().contains(HttpHeaderNames.RETRY_AFTER));
        assertFalse(response1.headers().contains("RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-Rate-Limit-Remaining"));
        assertFalse(response1.headers().contains("X-RateLimit-Remaining"));
        assertFalse(response1.headers().contains("X-RateLimit-Reset"));

        final AggregatedHttpResponse response2 = client.get("/http-throttle4").aggregate().get();
        System.out.println(response2.headers());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response2.status());

        assertTrue(response2.headers().contains(HttpHeaderNames.RETRY_AFTER));
        final long retryAfter2 = Long.parseLong(response2.headers().get(HttpHeaderNames.RETRY_AFTER));
        assertTrue(retryAfter2 <= 10L && retryAfter2 >= 5L);
        assertFalse(response2.headers().contains("RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-Rate-Limit-Remaining"));
        assertFalse(response2.headers().contains("X-RateLimit-Remaining"));
        assertFalse(response2.headers().contains("X-RateLimit-Reset"));
    }
}
