/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.resilience4j.circuitbreaker.FailedCircuitBreakerDecisionException;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class Resilience4jCircuitBreakerClientTest {

    @Test
    void testImmediateFailure() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRule rule = CircuitBreakerRule.onException();
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb), rule);

        final HttpClient client = mock(HttpClient.class);
        final RuntimeException t = new RuntimeException();
        when(client.execute(any(), any())).thenThrow(t);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onError(event -> {
            assertThat(event.getThrowable()).isSameAs(t);
            ab.set(true);
        });

        assertThatThrownBy(() -> decorator.apply(client).execute(ctx, req)).isSameAs(t);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void testRuleSuccess() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRule rule = CircuitBreakerRule.onException();
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb), rule);

        final HttpClient client = mock(HttpClient.class);
        when(client.execute(any(), any())).thenReturn(HttpResponse.of(200));
        ctx.eventLoop().schedule(() -> ctx.logBuilder().endResponse(), 2, TimeUnit.SECONDS);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onSuccess(event -> {
            assertThat(event.getElapsedDuration()).isGreaterThan(Duration.ofSeconds(1));
            ab.set(true);
        });

        decorator.apply(client).execute(ctx, req);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void testRuleFailure() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRule rule = CircuitBreakerRule.onException();
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb), rule);

        final HttpClient client = mock(HttpClient.class);
        when(client.execute(any(), any())).thenReturn(HttpResponse.of(200));
        final Throwable t = new Throwable();
        ctx.eventLoop().schedule(() -> ctx.logBuilder().endResponse(t), 2, TimeUnit.SECONDS);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onError(event -> {
            assertThat(event.getElapsedDuration()).isGreaterThan(Duration.ofSeconds(1));
            assertThat(event.getThrowable()).isSameAs(t);
            ab.set(true);
        });

        decorator.apply(client).execute(ctx, req);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void testRuleWithContentSuccess() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent =
                CircuitBreakerRuleWithContent.<HttpResponse>builder(HttpMethod.GET).thenSuccess();
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb),
                                                  ruleWithContent);

        final HttpClient client = mock(HttpClient.class);
        when(client.execute(any(), any())).thenReturn(HttpResponse.of(200));
        ctx.eventLoop().schedule(() -> ctx.logBuilder().endResponse(), 2, TimeUnit.SECONDS);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onSuccess(event -> {
            assertThat(event.getElapsedDuration()).isGreaterThan(Duration.ofSeconds(1));
            ab.set(true);
        });

        decorator.apply(client).execute(ctx, req);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void testRuleWithContentFailure() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent =
                CircuitBreakerRuleWithContent.<HttpResponse>builder(HttpMethod.GET).thenFailure();
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb),
                                                  ruleWithContent);

        final HttpClient client = mock(HttpClient.class);
        when(client.execute(any(), any())).thenReturn(HttpResponse.of(200));
        final Throwable t = new Throwable();
        ctx.eventLoop().schedule(() -> ctx.logBuilder().endResponse(t), 2, TimeUnit.SECONDS);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onError(event -> {
            assertThat(event.getElapsedDuration()).isGreaterThan(Duration.ofSeconds(1));
            assertThat(event.getThrowable()).isSameAs(t);
            ab.set(true);
        });

        decorator.apply(client).execute(ctx, req);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    @Test
    void testFailureDefaultThrowable() throws Exception {
        final HttpRequest req = HttpRequest.of(HttpMethod.GET, "/");
        final ClientRequestContext ctx = ClientRequestContext.of(req);

        final CircuitBreaker cb = CircuitBreaker.ofDefaults("cb");
        final CircuitBreakerRule rule = CircuitBreakerRule.onStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        final Function<? super HttpClient, CircuitBreakerClient> decorator =
                CircuitBreakerClient.newDecorator(Resilience4JCircuitBreakerClientHandler.of(cb), rule);

        final HttpClient client = mock(HttpClient.class);
        final HttpResponse httpResponse = HttpResponse.of(500);
        when(client.execute(any(), any())).thenReturn(httpResponse);
        ctx.eventLoop().schedule(() -> {
            ctx.logBuilder().responseHeaders(ResponseHeaders.of(500));
            ctx.logBuilder().endResponse();
        }, 2, TimeUnit.SECONDS);

        final AtomicBoolean ab = new AtomicBoolean();
        cb.getEventPublisher().onError(event -> {
            assertThat(event.getElapsedDuration()).isGreaterThan(Duration.ofSeconds(1));
            assertThat(event.getThrowable()).isInstanceOf(FailedCircuitBreakerDecisionException.class);
            ab.set(true);
        });

        decorator.apply(client).execute(ctx, req);

        await().untilTrue(ab);
        await().untilAsserted(() -> assertThat(cb.getMetrics().getNumberOfBufferedCalls()).isEqualTo(1));
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }
}
