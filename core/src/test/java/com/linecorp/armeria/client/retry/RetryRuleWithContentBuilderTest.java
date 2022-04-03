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

package com.linecorp.armeria.client.retry;

import static com.linecorp.armeria.client.retry.RetryRuleBuilderTest.assertBackoff;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.ClosedSessionException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

class RetryRuleWithContentBuilderTest {

    static final Backoff backoff = Backoff.fixed(10);
    ClientRequestContext ctx1;
    ClientRequestContext ctx2;

    @BeforeEach
    void setUp() {
        ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    @Test
    void shouldSetRule() {
        assertThatThrownBy(() -> RetryRuleWithContent.builder(HttpMethod.HEAD).thenBackoff())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one retry rule");

        assertThatThrownBy(
                () -> RetryRuleWithContent.builder().build(RetryDecision.retry(Backoff.ofDefault())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one retry rule");

        RetryRuleWithContent.builder(HttpMethod.HEAD)
                            .onResponse((unused, response) -> UnmodifiableFuture.completedFuture(true))
                            .thenBackoff();
    }

    @Test
    void retryWithContent() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.<HttpResponse>builder()
                        .onResponse((unused, response) -> {
                            return response.aggregate()
                                           .thenApply(content -> content.contentUtf8().contains("hello"));
                        })
                        .thenBackoff(backoff);

        final HttpResponse response = HttpResponse.of("hello");

        assertBackoff(rule.shouldRetry(ctx1, response, null)).isSameAs(backoff);
        assertThatThrownBy(
                () -> RetryRuleWithContent.builder().build(RetryDecision.retry(Backoff.ofDefault())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least one retry rule");
    }

    @Test
    void multipleHttpResponseSubscribeWithContent() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.of(
                        RetryRuleWithContent.onResponse((unused, response) -> {
                            return response.aggregate().thenApply(content -> false);
                        }),
                        RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                            return response.aggregate().thenApply(content -> false);
                        }).orElse(RetryRule.builder().onUnprocessed().thenBackoff(backoff)),
                        RetryRuleWithContent.<HttpResponse>builder()
                                .onResponse((unused, response) -> {
                                    return response.aggregate()
                                                   .thenApply(content -> "hello".equals(content.contentUtf8()));
                                }).thenBackoff());

        final HttpResponse response = HttpResponse.of("hello");
        final UnprocessedRequestException cause =
                UnprocessedRequestException.of(ClosedSessionException.get());
        response.abort(cause);
        try (HttpResponseDuplicator duplicator = response.toDuplicator()) {
            assertBackoff(rule.shouldRetry(ctx1, duplicator.duplicate(), cause)).isSameAs(backoff);
        }
    }

    @Test
    void multipleHttpResponseSubscribeWithCause() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.of(
                        RetryRuleWithContent
                                .<HttpResponse>builder()
                                .onResponse((unused, response) -> {
                                    return response.aggregate().thenApply(content -> false);
                                })
                                .onResponse((unused, response) -> {
                                    return response.aggregate().thenApply(content -> false);
                                })
                                .onResponse((unused, response) -> {
                                    return response.aggregate().thenApply(content -> false);
                                }).thenBackoff(),
                        RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                            return response.aggregate().thenApply(content -> false);
                        }).orElse(RetryRule.builder()
                                           .onUnprocessed()
                                           .thenBackoff(backoff)),
                        RetryRuleWithContent.<HttpResponse>builder()
                                .onResponse((unused, response) -> {
                                    return response.aggregate()
                                                   .thenApply(content -> "hello".equals(content.contentUtf8()));
                                }).thenBackoff());

        final UnprocessedRequestException cause =
                UnprocessedRequestException.of(ClosedSessionException.get());
        final HttpResponse response = HttpResponse.ofFailure(cause);
        try (HttpResponseDuplicator duplicator = response.toDuplicator()) {
            assertBackoff(rule.shouldRetry(ctx1, duplicator.duplicate(), cause)).isSameAs(backoff);
        }
    }

    @Test
    void multipleRpcResponse() {
        final RetryRuleWithContent<RpcResponse> rule =
                RetryRuleWithContent.of(
                        RetryRuleWithContent.onResponse((unused, response) -> {
                            return response.thenApply(content -> false);
                        }),
                        RetryRuleWithContent.<RpcResponse>onResponse((unused, response) -> {
                            return response.thenApply(content -> false);
                        }).orElse(RetryRule.onUnprocessed()),
                        RetryRuleWithContent.<RpcResponse>builder()
                                .onResponse((unused, response) -> {
                                    return response.thenApply("hello"::equals);
                                }).thenBackoff(backoff));

        final RpcResponse response = RpcResponse.of("hello");
        assertBackoff(rule.shouldRetry(ctx1, response, null)).isSameAs(backoff);
    }

    @Test
    void retryWithCause() {
        final RetryRuleWithContent<HttpResponse> rule =
                RetryRuleWithContent.<HttpResponse>builder()
                        .onResponse((unused, response) -> {
                            return response.aggregate()
                                           .thenApply(content -> content.contentUtf8().contains("hello"));
                        })
                        .onException(UnprocessedRequestException.class)
                        .thenBackoff(backoff);

        final HttpResponse response = HttpResponse.streaming();
        final UnprocessedRequestException cause =
                UnprocessedRequestException.of(ClosedSessionException.get());
        response.abort(cause);
        assertBackoff(rule.shouldRetry(ctx1, response, cause)).isSameAs(backoff);
    }

    @ArgumentsSource(RetryRuleProvider.class)
    @ParameterizedTest
    void combineRetryWithContent(RetryRuleWithContent<HttpResponse> rule) {
        final HttpResponse response1 = HttpResponse.of("hello");
        assertBackoff(rule.shouldRetry(ctx1, response1, null)).isSameAs(Backoff.ofDefault());

        final HttpResponse response2 = HttpResponse.of("world");
        ctx2.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.INTERNAL_SERVER_ERROR));
        assertBackoff(rule.shouldRetry(ctx2, response2, null)).isSameAs(backoff);
    }

    private static class RetryRuleProvider implements ArgumentsProvider {

        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            final RetryRuleWithContent<HttpResponse> rule1 =
                    RetryRuleWithContent.of(
                            RetryRuleWithContent.<HttpResponse>builder()
                                    .onResponse((unused, response) -> {
                                        return response.aggregate()
                                                       .thenApply(content -> content.contentUtf8()
                                                                                    .contains("hello"));
                                    })
                                    .thenBackoff(),
                            RetryRuleWithContent.<HttpResponse>builder()
                                    .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                    .thenBackoff(backoff));

            final RetryRuleWithContent<HttpResponse> rule2 =
                    RetryRuleWithContent.<HttpResponse>builder()
                            .onResponse((unused, response) -> {
                                return response.aggregate()
                                               .thenApply(content -> content.contentUtf8().contains("hello"));
                            })
                            .thenBackoff()
                            .orElse(RetryRule.builder()
                                             .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                             .thenBackoff(backoff));

            final RetryRuleWithContent<HttpResponse> rule3 =
                    RetryRuleWithContent.<HttpResponse>onResponse((unused, response) -> {
                        return response.aggregate()
                                       .thenApply(content -> content.contentUtf8().contains("hello"));
                    }).orElse(RetryRule.builder()
                                       .onStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                                       .thenBackoff(backoff));
            return Stream.of(rule1, rule2, rule3)
                         .map(Arguments::of);
        }
    }
}
