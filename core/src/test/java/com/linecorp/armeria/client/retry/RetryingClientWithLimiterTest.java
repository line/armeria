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

package com.linecorp.armeria.client.retry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.retry.limiter.RetryLimiter;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class RetryingClientWithLimiterTest {

    private static ClientFactory clientFactory;

    @BeforeAll
    static void beforeAll() {
        // Use different eventLoop from server's so that clients don't hang when the eventLoop in server hangs
        clientFactory = ClientFactory.builder().workerGroup(2).build();
    }

    @AfterAll
    static void afterAll() {
        clientFactory.closeAsync();
    }

    private AtomicInteger reqCount;

    @RegisterExtension
    final ServerExtension server = new ServerExtension() {
        @Override
        protected boolean runForEachTest() {
            return true;
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/500-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/503-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    if (reqCount.getAndIncrement() < 1) {
                        return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE);
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/always-500", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR);
                }
            });

            sb.service("/timeout-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    if (reqCount.getAndIncrement() < 1) {
                        // Simulate a slow response that will timeout
                        return HttpResponse.delayed(HttpResponse.of("Success"), Duration.ofSeconds(2));
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });

            sb.service("/immediate-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    return HttpResponse.of("Immediate success");
                }
            });

            sb.service("/exception-then-success", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                    if (reqCount.getAndIncrement() < 1) {
                        throw new RuntimeException("Simulated exception");
                    } else {
                        return HttpResponse.of("Succeeded after retry");
                    }
                }
            });
        }
    };

    @BeforeEach
    void setUp() {
        reqCount = new AtomicInteger();
    }

    private static RetryLimiter createMockLimiter(boolean shouldRetry) {
        final RetryLimiter mockLimiter = mock(RetryLimiter.class);
        when(mockLimiter.shouldRetry(any(), anyInt())).thenReturn(shouldRetry);
        return mockLimiter;
    }

    private static RetryConfig<HttpResponse> createRetryConfig(RetryRule rule, RetryLimiter limiter) {
        return RetryConfig.builder(rule)
                          .limiter(limiter)
                          .build();
    }

    private static RetryConfig<HttpResponse> createRetryConfig(RetryRuleWithContent<HttpResponse> rule,
                                                               RetryLimiter limiter) {
        return RetryConfig.builder(rule)
                          .limiter(limiter)
                          .maxContentLength(1024)
                          .build();
    }

    private static RetryConfig<HttpResponse> createRetryConfig(RetryRule rule, RetryLimiter limiter,
                                                               int maxTotalAttempts) {
        return RetryConfig.builder(rule)
                          .limiter(limiter)
                          .maxTotalAttempts(maxTotalAttempts)
                          .build();
    }

    private static RetryConfig<HttpResponse> createTimeoutRetryConfig(RetryLimiter limiter) {
        return RetryConfig.builder(RetryRule.builder()
                                            .onException(ResponseTimeoutException.class)
                                            .thenBackoff())
                          .limiter(limiter)
                          .responseTimeoutMillisForEachAttempt(500)
                          .build();
    }

    private WebClient createClient(RetryConfig<HttpResponse> retryConfig) {
        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .decorator(RetryingClient.builder(retryConfig).newDecorator())
                        .build();
    }

    private WebClient createClientWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return WebClient.builder(server.httpUri())
                        .factory(clientFactory)
                        .decorator(RetryingClient.builderWithMapping(mapping).newDecorator())
                        .build();
    }

    private static void verifyOnCompletedAttemptCalls(RetryLimiter limiter, int expectedCalls) {
        verify(limiter, times(expectedCalls)).onCompletedAttempt(any(), any(), anyInt());
    }

    private static void verifyOnCompletedAttemptCalls(RetryLimiter limiter, int expectedCalls,
                                                      int... attemptNumbers) {
        verify(limiter, times(expectedCalls)).onCompletedAttempt(any(), any(), anyInt());
        for (int attemptNumber : attemptNumbers) {
            verify(limiter, times(1)).onCompletedAttempt(any(), any(), eq(attemptNumber));
        }
    }

    private static void verifyShouldRetryCalls(RetryLimiter limiter, int expectedCalls, int... attemptNumbers) {
        verify(limiter, times(expectedCalls)).shouldRetry(any(), anyInt());
        for (int attemptNumber : attemptNumbers) {
            verify(limiter, times(1)).shouldRetry(any(), eq(attemptNumber));
        }
    }

    private static void verifyShouldRetryNeverCalled(RetryLimiter limiter) {
        verify(limiter, never()).shouldRetry(any(), anyInt());
    }

    @Test
    void retryLimiterShouldBeCalledOnCompletedAttempt() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRuleWithContent.<HttpResponse>builder()
                                    .onServerErrorStatus()
                                    .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldPreventRetryWhenShouldRetryReturnsFalse() {
        final RetryLimiter mockLimiter = createMockLimiter(false);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRuleWithContent.<HttpResponse>builder()
                                    .onServerErrorStatus()
                                    .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/always-500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        verifyOnCompletedAttemptCalls(mockLimiter, 1, 1);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldNotBeCalledOnFirstAttempt() {
        final RetryLimiter mockLimiter = mock(RetryLimiter.class);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/immediate-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Immediate success");

        verifyOnCompletedAttemptCalls(mockLimiter, 1);
        verifyShouldRetryNeverCalled(mockLimiter);
    }

    @Test
    void retryLimiterShouldBeCalledOnTimeoutRetry() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createTimeoutRetryConfig(mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/timeout-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldBeCalledOnExceptionRetry() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/exception-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldBeCalledWithCorrectAttemptNumbers() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter,
                3);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/always-500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        verifyOnCompletedAttemptCalls(mockLimiter, 3, 1, 2, 3);
        verifyShouldRetryCalls(mockLimiter, 2, 1, 2);
    }

    @Test
    void retryLimiterShouldNotBeCalledWhenMaxAttemptsReached() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter,
                2);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/always-500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldBeCalledForStreamingResponses() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldBeCalledForRetryRuleWithContent() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRuleWithContent.<HttpResponse>builder()
                                    .onServerErrorStatus()
                                    .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/503-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldNotBeCalledWhenRetryRuleDecidesNotToRetry() {
        final RetryLimiter mockLimiter = mock(RetryLimiter.class);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onStatus(HttpStatus.BAD_REQUEST) // Won't match 500
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/always-500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        verifyOnCompletedAttemptCalls(mockLimiter, 1);
        verifyShouldRetryNeverCalled(mockLimiter);
    }

    @Test
    void retryLimiterShouldBeCalledWithCorrectContextAndLog() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        final AggregatedHttpResponse res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2, 1, 2);
    }

    @Test
    void retryLimiterShouldBeCalledForMultipleRetries() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter,
                4);
        final WebClient client = createClient(retryConfig);

        // Reset counter to simulate multiple failures
        reqCount.set(0);

        final AggregatedHttpResponse res = client.get("/always-500").aggregate().join();
        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        verifyOnCompletedAttemptCalls(mockLimiter, 4);
        verifyShouldRetryCalls(mockLimiter, 3, 1, 2, 3);
    }

    @Test
    void retryLimiterShouldBeCalledForRetryConfigMapping() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        final RetryConfigMapping<HttpResponse> mapping = RetryConfigMapping.of(
                (ctx, req) -> "test-key",
                (ctx, req) -> createRetryConfig(
                        RetryRule.builder()
                                 .onServerErrorStatus()
                                 .thenBackoff(),
                        mockLimiter,
                        2)
        );
        final WebClient client = createClientWithMapping(mapping);

        final AggregatedHttpResponse res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldHandleExceptionInShouldRetry() {
        final RetryLimiter mockLimiter = mock(RetryLimiter.class);
        when(mockLimiter.shouldRetry(any(), anyInt())).thenThrow(new RuntimeException("Limiter exception"));
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter);
        final WebClient client = createClient(retryConfig);

        // The exception from shouldRetry should not prevent the retry
        final AggregatedHttpResponse res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }

    @Test
    void retryLimiterShouldHandleExceptionInOnCompletedAttempt() {
        final RetryLimiter mockLimiter = createMockLimiter(true);
        doThrow(new RuntimeException("onCompletedAttempt exception"))
                .when(mockLimiter).onCompletedAttempt(any(), any(), anyInt());
        final RetryConfig<HttpResponse> retryConfig = createRetryConfig(
                RetryRule.builder()
                         .onServerErrorStatus()
                         .thenBackoff(),
                mockLimiter,
                2);
        final WebClient client = createClient(retryConfig);

        // The exception from onCompletedAttempt should not prevent the retry
        final AggregatedHttpResponse res = client.get("/500-then-success").aggregate().join();
        assertThat(res.contentUtf8()).isEqualTo("Succeeded after retry");

        verifyOnCompletedAttemptCalls(mockLimiter, 2);
        verifyShouldRetryCalls(mockLimiter, 1, 1);
    }
}
