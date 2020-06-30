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

package com.linecorp.armeria.client.circuitbreaker;

import static com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleBuilderTest.assertFuture;

import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class CircuitBreakerRuleWithContentBuilderTest {

    ClientRequestContext ctx1;
    ClientRequestContext ctx2;

    @BeforeEach
    void setUp() {
        ctx1 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        ctx2 = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
    }

    @Test
    void shouldAllowEmptyRule() {
        CircuitBreakerRuleWithContent.builder().thenFailure();
        CircuitBreakerRuleWithContent.builder().thenSuccess();
        CircuitBreakerRuleWithContent.builder().thenIgnore();
    }

    @ArgumentsSource(CircuitBreakerRuleSource.class)
    @ParameterizedTest
    void shouldReportAsWithContent(String message, CircuitBreakerDecision decision) {
        final CircuitBreakerRuleWithContent<HttpResponse> rule =
                CircuitBreakerRuleWithContent
                        .<HttpResponse>builder()
                        .onResponse((unused, response) -> response.aggregate().thenApply(content -> false))
                        .onResponse((unused, response) -> response.aggregate().thenApply(content -> false))
                        .onResponse((unused, response) -> {
                            return response.aggregate()
                                           .thenApply(content -> content.contentUtf8().contains(message));
                        })
                        .thenSuccess()
                        .orElse(CircuitBreakerRuleWithContent
                                        .<HttpResponse>builder()
                                        .thenFailure());

        ctx1.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        assertFuture(rule.shouldReportAsSuccess(ctx1, HttpResponse.of("Hello Armeria!"), null))
                .isSameAs(decision);
    }

    private static final class CircuitBreakerRuleSource implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception {
            return Stream.of(Arguments.of("Hello", CircuitBreakerDecision.success()),
                             Arguments.of("World", CircuitBreakerDecision.failure()));
        }
    }
}
