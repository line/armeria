/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.server.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.LogWriter;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.NetUtil;

class HealthCheckServiceTest {

    private static final SettableHealthChecker checker = new SettableHealthChecker();
    private static final AtomicReference<Boolean> capturedHealthy = new AtomicReference<>();
    private static final HealthChecker unfinishedHealthChecker = HealthChecker.of(CompletableFuture::new,
                                                                                  Duration.ofDays(1));
    private static final Logger logger = mock(Logger.class);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/hc", HealthCheckService.of(checker));
            sb.service("/hc_unfinished_1", HealthCheckService.of(unfinishedHealthChecker));
            sb.service("/hc_unfinished_2", HealthCheckService.of(unfinishedHealthChecker));
            sb.service("/hc_long_polling_disabled", HealthCheckService.builder()
                                                                      .longPolling(0)
                                                                      .build());
            sb.service("/hc_updatable", HealthCheckService.builder()
                                                          .updatable(true)
                                                          .build());
            sb.service("/hc_update_listener", HealthCheckService.builder()
                                                                .updatable(true)
                                                                .updateListener(capturedHealthy::set)
                                                                .build());
            sb.service("/hc_unhealthy_at_startup", HealthCheckService.builder()
                                                                     .updatable(true)
                                                                     .startUnhealthy()
                                                                     .build());
            sb.service("/hc_custom",
                       HealthCheckService.builder()
                                         .healthyResponse(AggregatedHttpResponse.of(
                                                 HttpStatus.OK,
                                                 MediaType.PLAIN_TEXT_UTF_8,
                                                 "ok"))
                                         .unhealthyResponse(AggregatedHttpResponse.of(
                                                 HttpStatus.SERVICE_UNAVAILABLE,
                                                 MediaType.PLAIN_TEXT_UTF_8,
                                                 "not ok"))
                                         .updatable((ctx, req) -> {
                                             if (req.method() != HttpMethod.PUT) {
                                                 throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED);
                                             }
                                             return req.aggregate().thenApply(aReq -> {
                                                 final String content = aReq.contentAscii();
                                                 switch (content) {
                                                     case "OK":
                                                         return HealthCheckUpdateResult.HEALTHY;
                                                     case "KO":
                                                         return HealthCheckUpdateResult.UNHEALTHY;
                                                     case "NOOP":
                                                         return HealthCheckUpdateResult.AS_IS;
                                                     default:
                                                         throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
                                                 }
                                             });
                                         })
                                         .build());
            sb.decorator(LoggingService.builder()
                                       .logWriter(LogWriter.of(logger))
                                       .newDecorator());
            sb.gracefulShutdownTimeout(Duration.ofSeconds(10), Duration.ofSeconds(10));
            sb.disableServerHeader();
            sb.disableDateHeader();
        }
    };

    @AfterAll
    static void ensureScheduledHealthCheckerStopped() {
        assertThat(((ScheduledHealthChecker) unfinishedHealthChecker).isActive()).isTrue();
        server.stop().join();
        assertThat(((ScheduledHealthChecker) unfinishedHealthChecker).isActive()).isFalse();
    }

    @BeforeEach
    void setUp() {
        reset(logger);
        when(logger.isDebugEnabled()).thenReturn(true);
        checker.setHealthy(true);
    }

    @AfterEach
    void ensureNoPendingResponses() {
        server.server().serviceConfigs().forEach(cfg -> {
            final HealthCheckService service = cfg.service().as(HealthCheckService.class);
            if (service != null) {
                await().untilAsserted(() -> {
                    if (service.pendingHealthyResponses != null) {
                        assertThat(service.pendingHealthyResponses).isEmpty();
                    }
                    if (service.pendingUnhealthyResponses != null) {
                        assertThat(service.pendingUnhealthyResponses).isEmpty();
                    }
                });
            }
        });
    }

    @Test
    void getWhenHealthy() throws Exception {
        assertResponseEquals("GET /hc HTTP/1.0",
                             "HTTP/1.1 200 OK\r\n" +
                             "content-type: application/json; charset=utf-8\r\n" +
                             "armeria-lphc: 60, 5\r\n" +
                             "content-length: 16\r\n" +
                             // As Armeria is not fully compatible with HTTP/1.0,
                             // an HTTP/1.1 response including "connection: close" header is returned.
                             "connection: close\r\n\r\n" +
                             "{\"healthy\":true}");
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void getWhenUnhealthy() throws Exception {
        checker.setHealthy(false);
        assertResponseEquals("GET /hc HTTP/1.0",
                             "HTTP/1.1 503 Service Unavailable\r\n" +
                             "content-type: application/json; charset=utf-8\r\n" +
                             "armeria-lphc: 60, 5\r\n" +
                             "content-length: 17\r\n" +
                             "connection: close\r\n\r\n" +
                             "{\"healthy\":false}");
        await().untilAsserted(() -> {
            verify(logger).debug(anyString());
        });
    }

    @Test
    void headWhenHealthy() throws Exception {
        assertResponseEquals("HEAD /hc HTTP/1.0",
                             "HTTP/1.1 200 OK\r\n" +
                             "content-type: application/json; charset=utf-8\r\n" +
                             "armeria-lphc: 60, 5\r\n" +
                             "content-length: 16\r\n" +
                             "connection: close\r\n\r\n");
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void headWhenUnhealthy() throws Exception {
        checker.setHealthy(false);
        assertResponseEquals("HEAD /hc HTTP/1.0",
                             "HTTP/1.1 503 Service Unavailable\r\n" +
                             "content-type: application/json; charset=utf-8\r\n" +
                             "armeria-lphc: 60, 5\r\n" +
                             "content-length: 17\r\n" +
                             "connection: close\r\n\r\n");
        verify(logger).debug(anyString());
    }

    private static void assertResponseEquals(String request, String expectedResponse) throws Exception {
        final int port = server.httpPort();
        try (Socket s = new Socket(NetUtil.LOCALHOST, port)) {
            s.setSoTimeout(10000);
            final InputStream in = s.getInputStream();
            final OutputStream out = s.getOutputStream();
            out.write((request + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));

            // Should neither be chunked nor have content.
            assertThat(new String(ByteStreams.toByteArray(in), StandardCharsets.UTF_8))
                    .isEqualTo(expectedResponse);
        }
    }

    @Test
    void waitUntilUnhealthy() {
        final CompletableFuture<AggregatedHttpResponse> f = sendLongPollingGet("healthy");

        // Should not wake up until the server becomes unhealthy.
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);

        // Make the server unhealthy so the response comes in.
        checker.setHealthy(false);
        assertThat(f.join()).isEqualTo(AggregatedHttpResponse.of(
                ImmutableList.of(ResponseHeaders.builder(HttpStatus.PROCESSING)
                                                .set("armeria-lphc", "60, 5")
                                                .build()),
                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":false}"),
                HttpHeaders.of()));
    }

    @Test
    void waitUntilUnhealthyWithImmediateWakeup() throws Exception {
        // Make the server unhealthy.
        checker.setHealthy(false);

        final CompletableFuture<AggregatedHttpResponse> f = sendLongPollingGet("healthy");

        // The server is unhealthy already, so the response has to come in immediately.
        assertThat(f.get()).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":false}")));
    }

    @Test
    void waitUntilHealthy() throws Exception {
        // Make the server unhealthy.
        checker.setHealthy(false);

        final CompletableFuture<AggregatedHttpResponse> f = sendLongPollingGet("unhealthy");

        // Should not wake up until the server becomes unhealthy.
        assertThatThrownBy(() -> f.get(1, TimeUnit.SECONDS))
                .isInstanceOf(TimeoutException.class);

        // Make the server healthy so the response comes in.
        checker.setHealthy(true);
        assertThat(f.get()).isEqualTo(AggregatedHttpResponse.of(
                ImmutableList.of(ResponseHeaders.builder(HttpStatus.PROCESSING)
                                                .set("armeria-lphc", "60, 5")
                                                .build()),
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":true}"),
                HttpHeaders.of()));
    }

    @Test
    void waitUntilHealthyWithImmediateWakeUp() throws Exception {
        final CompletableFuture<AggregatedHttpResponse> f = sendLongPollingGet("unhealthy");

        // The server is healthy already, so the response has to come in immediately.
        assertThat(f.get()).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":true}")));
    }

    @Test
    void waitTimeout() throws Exception {
        final AggregatedHttpResponse res = sendLongPollingGet("healthy", 1).get();
        assertThat(res).isEqualTo(AggregatedHttpResponse.of(
                ImmutableList.of(ResponseHeaders.builder(HttpStatus.PROCESSING)
                                                .set("armeria-lphc", "60, 5")
                                                .build()),
                ResponseHeaders.builder()
                               .endOfStream(true)
                               .status(HttpStatus.NOT_MODIFIED)
                               .contentType(MediaType.JSON_UTF_8)
                               .set("armeria-lphc", "60, 5")
                               .build(),
                HttpData.empty(),
                HttpHeaders.of()));
    }

    @Test
    void waitWithWrongMethod() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(
                RequestHeaders.of(HttpMethod.POST, "/hc_custom",
                                  HttpHeaderNames.PREFER, "wait=60",
                                  HttpHeaderNames.IF_NONE_MATCH, "\"healthy\"")).aggregate();
        assertThat(f.get().status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void waitWithWrongTimeout() throws Exception {
        final AggregatedHttpResponse res = sendLongPollingGet("healthy", -1).get();
        assertThat(res.status()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void waitWithOtherETag() throws Exception {
        // A never-matching etag must disable polling.
        final AggregatedHttpResponse res = sendLongPollingGet("whatever", 1).get();
        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void longPollingDisabled() throws Exception {
        final WebClient client = WebClient.of(server.httpUri());
        final CompletableFuture<AggregatedHttpResponse> f = client.execute(
                RequestHeaders.of(HttpMethod.GET, "/hc_long_polling_disabled",
                                  HttpHeaderNames.PREFER, "wait=60",
                                  HttpHeaderNames.IF_NONE_MATCH, "\"healthy\"")).aggregate();
        assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "0, 0"),
                HttpData.ofUtf8("{\"healthy\":true}")));
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void notUpdatableByDefault() throws Exception {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());
        final AggregatedHttpResponse res = client.execute(RequestHeaders.of(HttpMethod.POST, "/hc"),
                                                          "{\"healthy\":false}");
        assertThat(res.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        verifyDebugEnabled(logger);
        verifyNoMoreInteractions(logger);
    }

    @Test
    void updateUsingPutOrPost() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        // Make unhealthy.
        final AggregatedHttpResponse res1 = client.execute(RequestHeaders.of(HttpMethod.PUT, "/hc_updatable"),
                                                           "{\"healthy\":false}");
        assertThat(res1).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":false}")));

        // Make healthy.
        final AggregatedHttpResponse res2 = client.execute(RequestHeaders.of(HttpMethod.POST, "/hc_updatable"),
                                                           "{\"healthy\":true}");
        assertThat(res2).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":true}")));
    }

    @Test
    void updateUsingPatch() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        // Make unhealthy.
        final AggregatedHttpResponse res1 = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, "/hc_updatable"),
                "[{\"op\":\"replace\",\"path\":\"/healthy\",\"value\":false}]");
        assertThat(res1).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":false}")));

        // Make healthy.
        final AggregatedHttpResponse res2 = client.execute(
                RequestHeaders.of(HttpMethod.PATCH, "/hc_updatable"),
                "[{\"op\":\"replace\",\"path\":\"/healthy\",\"value\":true}]");
        assertThat(res2).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.JSON_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("{\"healthy\":true}")));
    }

    @Test
    void updateListener() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        capturedHealthy.set(null);

        // Make unhealthy.
        client.execute(RequestHeaders.of(HttpMethod.POST, "/hc_update_listener"), "{\"healthy\":false}");
        assertThat(capturedHealthy.get()).isFalse();

        capturedHealthy.set(null);

        // Make healthy.
        client.execute(RequestHeaders.of(HttpMethod.POST, "/hc_update_listener"), "{\"healthy\":true}");
        assertThat(capturedHealthy.get()).isTrue();
    }

    @Test
    void startUnhealthy() throws Exception {
        assertResponseEquals("GET /hc_unhealthy_at_startup HTTP/1.0",
                             "HTTP/1.1 503 Service Unavailable\r\n" +
                             "content-type: application/json; charset=utf-8\r\n" +
                             "armeria-lphc: 60, 5\r\n" +
                             "content-length: 17\r\n" +
                             "connection: close\r\n\r\n" +
                             "{\"healthy\":false}");
    }

    @Test
    void custom() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        // Make unhealthy.
        final AggregatedHttpResponse res1 = client.execute(RequestHeaders.of(HttpMethod.PUT, "/hc_custom"),
                                                           "KO");
        assertThat(res1).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.SERVICE_UNAVAILABLE,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("not ok")));

        // Make healthy.
        final AggregatedHttpResponse res2 = client.execute(RequestHeaders.of(HttpMethod.PUT, "/hc_custom"),
                                                           "OK");
        assertThat(res2).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("ok")));

        // Send a no-op request.
        final AggregatedHttpResponse res3 = client.execute(RequestHeaders.of(HttpMethod.PUT, "/hc_custom"),
                                                           "NOOP");
        assertThat(res3).isEqualTo(AggregatedHttpResponse.of(
                ResponseHeaders.of(HttpStatus.OK,
                                   HttpHeaderNames.CONTENT_TYPE, MediaType.PLAIN_TEXT_UTF_8,
                                   "armeria-lphc", "60, 5"),
                HttpData.ofUtf8("ok")));
    }

    @Test
    void customError() {
        final BlockingWebClient client = BlockingWebClient.of(server.httpUri());

        // Use an unsupported method.
        final AggregatedHttpResponse res1 = client.execute(RequestHeaders.of(HttpMethod.PATCH, "/hc_custom"));
        assertThat(res1.status()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);

        // Send a wrong command.
        final AggregatedHttpResponse res2 = client.execute(RequestHeaders.of(HttpMethod.PUT, "/hc_custom"),
                                                           "BAD");
        assertThat(res2.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static void verifyDebugEnabled(Logger logger) {
        // Do not log for health check requests unless TransientServiceOption is enabled.
        verify(logger, never()).isDebugEnabled();
    }

    private static CompletableFuture<AggregatedHttpResponse> sendLongPollingGet(String healthiness) {
        return sendLongPollingGet(healthiness, 120);
    }

    private static CompletableFuture<AggregatedHttpResponse> sendLongPollingGet(String healthiness,
                                                                                int timeoutSeconds) {
        final WebClient client = WebClient.of(server.httpUri());
        return client.execute(RequestHeaders.of(HttpMethod.GET, "/hc",
                                                HttpHeaderNames.PREFER, "wait=" + timeoutSeconds,
                                                HttpHeaderNames.IF_NONE_MATCH,
                                                '"' + healthiness + '"')).aggregate();
    }
}
