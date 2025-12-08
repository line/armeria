/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.withinPercentage;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.RefusedStreamException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class RetryTest {

    //language=YAML
    private static final String bootstrap =
            """
                static_resources:
                  listeners:
                  - name: my-listener
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                        stat_prefix: http
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                              - match:
                                  prefix: /
                                route:
                                  cluster: my-cluster
                                  retry_policy: %s
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: my-cluster
                    type: STATIC
                    load_assignment:
                      cluster_name: my-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: 8080
                """;

    public static Stream<Arguments> retryOnResponseHeader_args() {
        return Stream.of(
                // RETRY_ON_5XX
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500), 2),
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(502), 2),
                // RETRY_ON_GATEWAY_ERROR
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(502), 2),
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(503), 2),
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(504), 2),
                // RETRY_ON_RETRIABLE_4XX
                Arguments.of("""
                             {
                               retry_on: "retriable-4xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(409), 2),
                // RETRY_ON_ENVOY_RATE_LIMITED
                Arguments.of("""
                             {
                               retry_on: "envoy-ratelimited",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(429).add("x-envoy-ratelimited", "true").build(), 2),
                // RETRY_ON_RETRIABLE_STATUS_CODES
                Arguments.of("""
                             {
                               retry_on: "retriable-status-codes",
                               retriable_status_codes: [418, 422],
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(418), 2),
                Arguments.of("""
                             {
                               retry_on: "retriable-status-codes",
                               retriable_status_codes: [418, 422],
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(422), 2),
                // RETRY_ON_RETRIABLE_HEADERS
                Arguments.of("""
                             {
                               retry_on: "retriable-headers",
                               retriable_headers: [
                                 {
                                   name: "x-should-retry",
                                   string_match: { exact: "true" }
                                 }
                               ],
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("x-should-retry", "true").build(), 2),
                // RETRY_ON_GRPC_CANCELLED
                Arguments.of("""
                             {
                               retry_on: "cancelled",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "1").build(), 2),
                // RETRY_ON_GRPC_DEADLINE_EXCEEDED
                Arguments.of("""
                             {
                               retry_on: "deadline-exceeded",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "4").build(), 2),
                // RETRY_ON_GRPC_RESOURCE_EXHAUSTED
                Arguments.of("""
                             {
                               retry_on: "resource-exhausted",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "8").build(), 2),
                // RETRY_ON_GRPC_UNAVAILABLE
                Arguments.of("""
                             {
                               retry_on: "unavailable",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "14").build(), 2),
                // RETRY_ON_GRPC_INTERNAL
                Arguments.of("""
                             {
                               retry_on: "internal",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "13").build(), 2),
                // No retry cases
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(200), 0),
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500), 0),
                Arguments.of("""
                             {
                               retry_on: "retriable-4xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(400), 0)
        );
    }

    @ParameterizedTest
    @MethodSource("retryOnResponseHeader_args")
    void retryOnResponseHeader(String retryOptions, ResponseHeaders responseHeaders, int numRetries) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> {
                                     return HttpResponse.of(responseHeaders);
                                 })
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }
            assertThat(ctx.log().children()).hasSize(numRetries + 1);
        }
    }

    public static Stream<Arguments> retryOnReset_args() {
        return Stream.of(
                // RETRY_ON_RESET - retries on any exception
                Arguments.of("""
                             {
                               retry_on: "reset",
                               num_retries: 2
                             }
                             """, new RuntimeException("Generic exception"), 2),
                // RETRY_ON_RESET_BEFORE_REQUEST - retries on UnprocessedRequestException
                Arguments.of("""
                             {
                               retry_on: "reset-before-request",
                               num_retries: 2
                             }
                             """, UnprocessedRequestException.of(new RuntimeException("Before request")), 2),
                // RETRY_ON_REFUSED_STREAM - retries on RefusedStreamException wrapped in
                // UnprocessedRequestException
                Arguments.of("""
                             {
                               retry_on: "refused-stream",
                               num_retries: 2
                             }
                             """, UnprocessedRequestException.of(RefusedStreamException.get()), 2),
                // No retry cases
                Arguments.of("""
                             {
                               retry_on: "reset-before-request",
                               num_retries: 2
                             }
                             """, new RuntimeException("Not UnprocessedRequestException"), 0),
                Arguments.of("""
                             {
                               retry_on: "refused-stream",
                               num_retries: 2
                             }
                             """, UnprocessedRequestException.of(
                        new RuntimeException("Not RefusedStreamException")), 0)
        );
    }

    @ParameterizedTest
    @MethodSource("retryOnReset_args")
    void retryOnReset(String retryOptions, Throwable cause, int numRetries) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                assertThatThrownBy(() -> {
                    WebClient.builder(preprocessor)
                             .decorator((delegate, ctx0, req) -> HttpResponse.ofFailure(cause))
                             .build()
                             .blocking()
                             .execute(HttpRequest.of(HttpMethod.GET, "/"));
                }).isEqualTo(cause);
                ctx = captor.get();
            }
            assertThat(ctx.log().children()).hasSize(numRetries + 1);
        }
    }

    public static Stream<Arguments> retryLimit_args() {
        return Stream.of(
                // Test explicit num_retries limit
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 3
                             }
                             """, ResponseHeaders.of(500), 3),
                // Test omitted num_retries (should use Armeria's default)
                Arguments.of("""
                             {
                               retry_on: "5xx"
                             }
                             """, ResponseHeaders.of(500), Flags.defaultMaxTotalAttempts() - 1)
        );
    }

    @ParameterizedTest
    @MethodSource("retryLimit_args")
    void retryLimit(String retryOptions, ResponseHeaders responseHeaders, int expectedRetries) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(responseHeaders))
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }

            // Verify the exact number of retry attempts (original + retries)
            assertThat(ctx.log().children()).hasSize(expectedRetries + 1);
        }
    }

    public static Stream<Arguments> perTryTimeout_args() {
        return Stream.of(
                // Test explicit per_try_timeout
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2,
                               per_try_timeout: "5s"
                             }
                             """, ResponseHeaders.of(500), 5000L),
                // Test omitted per_try_timeout (should use default timeout)
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500), Flags.defaultResponseTimeoutMillis())
        );
    }

    @ParameterizedTest
    @MethodSource("perTryTimeout_args")
    void perTryTimeout(String retryOptions, ResponseHeaders responseHeaders, long expectedTimeoutMillis) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        final ArrayDeque<ClientRequestContext> childCtxs = new ArrayDeque<>();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> {
                                     childCtxs.add(ctx0);
                                     return HttpResponse.of(responseHeaders);
                                 })
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }

            // Verify retries occurred
            assertThat(ctx.log().children()).hasSize(3); // 2 retries + 1 original = 3 total
            assertThat(childCtxs).hasSize(3);

            for (ClientRequestContext childCtx : childCtxs) {
                // Allow small tolerance for timing variations
                assertThat(childCtx.responseTimeoutMillis())
                        .isCloseTo(expectedTimeoutMillis, withinPercentage(10.0));
            }
        }
    }

    public static Stream<Arguments> retryBackoff_args() {
        return Stream.of(
                // Test custom base_interval (100ms base)
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2,
                               retry_back_off: {
                                 base_interval: "0.1s",
                                 max_interval: "1s"
                               }
                             }
                             """, ResponseHeaders.of(500), 100L, 200L),
                // Test default backoff (25ms base)
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500), 25L, 50L),
                // Test different intervals (50ms base)
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2,
                               retry_back_off: {
                                 base_interval: "0.05s",
                                 max_interval: "2s"
                               }
                             }
                             """, ResponseHeaders.of(500), 50L, 100L),
                // Test max_interval capping - base would double to 200ms but max caps at 150ms
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2,
                               retry_back_off: {
                                 base_interval: "0.1s",
                                 max_interval: "0.15s"
                               }
                             }
                             """, ResponseHeaders.of(500), 100L, 150L)
        );
    }

    @ParameterizedTest
    @MethodSource("retryBackoff_args")
    void retryBackoff(String retryOptions, ResponseHeaders responseHeaders,
                      long expectedFirstDelayMillis, long expectedSecondDelayMillis) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(responseHeaders))
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }

            // Verify retry attempts occurred
            assertThat(ctx.log().children()).hasSize(3); // 2 retries + 1 original = 3 total
            // Verify backoff timing using unified end-to-start measurement
            verifyBackoffTiming(ctx, List.of(expectedFirstDelayMillis, expectedSecondDelayMillis));
        }
    }

    public static Stream<Arguments> rateLimitedBackoff_args() {
        return Stream.of(
                // Test rate limited backoff with retry-after header in seconds
                Arguments.of("""
                              {
                                retry_on: "5xx",
                                num_retries: 2,
                                rate_limited_retry_back_off: {
                                  reset_headers: [{
                                    name: "retry-after",
                                    format: "SECONDS"
                                  }],
                                  max_interval: "10s"
                                }
                              }
                              """,
                             ResponseHeaders.builder(500).add("retry-after", "1").build(), 1000L, 1000L),
                // Test rate limited backoff exceeding max_interval (should skip)
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2,
                               rate_limited_retry_back_off: {
                                 reset_headers: [{
                                   name: "retry-after",
                                   format: "SECONDS"
                                 }],
                                 max_interval: "1s"
                               }
                             }
                             """,
                             ResponseHeaders.builder(500).add("retry-after", "5").build(),
                             25L, 50L) // Falls back to exponential: 25ms (1st retry), 50ms (2nd retry)
        );
    }

    @ParameterizedTest
    @MethodSource("rateLimitedBackoff_args")
    void rateLimitedBackoff(String retryOptions, ResponseHeaders responseHeaders,
                            long expectedFirstDelayMillis, long expectedSecondDelayMillis) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                WebClient.builder(preprocessor)
                         .decorator((delegate, ctx0, req) -> HttpResponse.of(responseHeaders))
                         .build()
                         .blocking()
                         .execute(HttpRequest.of(HttpMethod.GET, "/"));
                ctx = captor.get();
            }

            // Verify retry attempts occurred
            assertThat(ctx.log().children()).hasSize(3); // 2 retries + 1 original = 3 total
            // Verify backoff timing using unified end-to-start measurement
            verifyBackoffTiming(ctx, List.of(expectedFirstDelayMillis, expectedSecondDelayMillis));
        }
    }

    private static void verifyBackoffTiming(ClientRequestContext ctx, List<Long> expectedDelaysMillis) {
        final var children = ctx.log().children();
        final int numRetries = expectedDelaysMillis.size();

        // Verify we have enough attempts to measure the expected delays
        assertThat(children).hasSizeGreaterThanOrEqualTo(numRetries + 1);

        // Measure actual backoff delays from end of previous attempt to start of next attempt
        for (int i = 0; i < numRetries; i++) {
            final long previousAttemptEndTimeNanos =
                    children.get(i).ensureComplete().responseEndTimeNanos();
            final long currentAttemptStartTimeNanos =
                    children.get(i + 1).ensureComplete().requestStartTimeNanos();

            final long actualDelayMillis = TimeUnit.NANOSECONDS.toMillis(
                    currentAttemptStartTimeNanos - previousAttemptEndTimeNanos);
            final long expectedDelayMillis = expectedDelaysMillis.get(i);

            // Verify delay matches expected value (tolerance for 50% jitter + responseTimeout recalc)
            assertThat(actualDelayMillis).isCloseTo(expectedDelayMillis, withinPercentage(100.0));
        }
    }

    public static Stream<Arguments> retryOnRequestHeaders_args() {
        return Stream.of(
                // Test x-envoy-retry-on header adds retry policies (with existing config retry_on)
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-on", "5xx").build(), 2),
                // Test x-envoy-retry-on with multiple policies
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(502),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-on", "5xx,gateway-error").build(), 2),
                // Test x-envoy-retry-grpc-on header adds gRPC retry policies
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("grpc-status", "14").build(),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-grpc-on", "unavailable").build(), 2),
                // Test x-envoy-max-retries header overrides config
                Arguments.of("""
                             {
                               retry_on: "5xx",
                               num_retries: 3
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-max-retries", "5").build(), 5),
                // Test x-envoy-retriable-status-codes header adds to existing config status codes
                Arguments.of("""
                             {
                               retry_on: "retriable-status-codes",
                               retriable_status_codes: [500, 502],
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retriable-status-codes", "418,422").build(), 2),
                // Test header-added status codes also work
                Arguments.of("""
                             {
                               retry_on: "retriable-status-codes",
                               retriable_status_codes: [500, 502],
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(418),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retriable-status-codes", "418,422").build(), 2),
                // Test x-envoy-retriable-header-names header adds retriable headers
                Arguments.of("""
                             {
                               retry_on: "retriable-headers",
                               num_retries: 2
                             }
                             """, ResponseHeaders.builder(200).add("x-should-retry", "yes").build(),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retriable-header-names", "x-should-retry").build(), 2),
                // Test multiple request headers combined
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 1
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-on", "5xx")
                                           .add("x-envoy-max-retries", "4").build(), 4),
                // Test no retry when request header doesn't match response
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(400),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-on", "5xx").build(), 0),
                // Test gRPC retry header doesn't match HTTP status
                Arguments.of("""
                             {
                               retry_on: "gateway-error",
                               num_retries: 2
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-grpc-on", "unavailable").build(), 0)
        );
    }

    @ParameterizedTest
    @MethodSource("retryOnRequestHeaders_args")
    void retryOnRequestHeaders(String retryOptions, ResponseHeaders responseHeaders,
                               RequestHeaders requestHeaders, int expectedRetries) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(responseHeaders))
                                 .build()
                                 .blocking()
                                 .execute(requestHeaders);
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }
            assertThat(ctx.log().children()).hasSize(expectedRetries + 1);
        }
    }

    public static Stream<Arguments> retriableRequestHeaders_args() {
        return Stream.of(
                // Test retriable_request_headers matching (should retry)
                Arguments.of("""
                             {
                               num_retries: 2,
                               retriable_request_headers: [
                                 {
                                   name: "x-can-retry",
                                   string_match: { exact: "true" }
                                 }
                               ]
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-can-retry", "true")
                                           .add("x-envoy-retry-on", "5xx")
                                           .add("x-envoy-max-retries", "2").build(), 2),
                // Test retriable_request_headers not matching (should not retry even with retry header)
                Arguments.of("""
                             {
                               num_retries: 2,
                               retriable_request_headers: [
                                 {
                                   name: "x-can-retry",
                                   string_match: { exact: "true" }
                                 }
                               ]
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-can-retry", "false")
                                           .add("x-envoy-retry-on", "5xx")
                                           .add("x-envoy-max-retries", "2").build(), 0),
                // Test retriable_request_headers missing header (should not retry)
                Arguments.of("""
                             {
                               num_retries: 2,
                               retriable_request_headers: [
                                 {
                                   name: "x-can-retry",
                                   string_match: { exact: "true" }
                                 }
                               ]
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-envoy-retry-on", "5xx")
                                           .add("x-envoy-max-retries", "2").build(), 0),
                // Test multiple retriable_request_headers (OR condition)
                Arguments.of("""
                             {
                               num_retries: 2,
                               retriable_request_headers: [
                                 {
                                   name: "x-can-retry",
                                   string_match: { exact: "true" }
                                 },
                                 {
                                   name: "x-force-retry",
                                   string_match: { exact: "yes" }
                                 }
                               ]
                             }
                             """, ResponseHeaders.of(500),
                             RequestHeaders.builder(HttpMethod.GET, "/")
                                           .add("x-force-retry", "yes")
                                           .add("x-envoy-retry-on", "5xx")
                                           .add("x-envoy-max-retries", "2").build(), 2)
        );
    }

    @ParameterizedTest
    @MethodSource("retriableRequestHeaders_args")
    void retriableRequestHeaders(String retryOptions, ResponseHeaders responseHeaders,
                                 RequestHeaders requestHeaders, int expectedRetries) {
        final String formattedBootstrap = bootstrap.formatted(retryOptions);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(formattedBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(responseHeaders))
                                 .build()
                                 .blocking()
                                 .execute(requestHeaders);
                assertThat(res.status().code()).isEqualTo(responseHeaders.status().code());
                ctx = captor.get();
            }
            assertThat(ctx.log().children()).hasSize(expectedRetries + 1);
        }
    }
}
