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
package com.linecorp.armeria.client.endpoint.healthcheck;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import javax.annotation.Nullable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class HealthCheckedEndpointGroupLongPollingPingTest {

    private static final Duration RETRY_INTERVAL = Duration.ofSeconds(3);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/ping", HealthCheckService.builder()
                                                  .longPolling(Duration.ofSeconds(60), 0,
                                                               Duration.ofSeconds(1))
                                                  .build());

            sb.service("/no_ping_after_initial_ping", (ctx, req) -> {
                // Send a healthy response for non-long-polling request
                // so that the client sends a long-polling request next time.
                if (!req.headers().contains(HttpHeaderNames.IF_NONE_MATCH)) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                                              "armeria-lphc", "60, 1"));
                }

                // Do not send anything but the initial ping.
                final HttpResponseWriter res = HttpResponse.streaming();
                res.write(ResponseHeaders.of(HttpStatus.PROCESSING,
                                             "armeria-lphc", "60, 1"));
                return res;
            });

            sb.service("/no_ping_at_all", (ctx, req) -> {
                // Send a healthy response for non-long-polling request
                // so that the client sends a long-polling request next time.
                if (!req.headers().contains(HttpHeaderNames.IF_NONE_MATCH)) {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK,
                                                              "armeria-lphc", "60, 1"));
                }

                // Do not send anything, even the initial ping.
                return HttpResponse.streaming();
            });
        }
    };

    private static final AttributeKey<BlockingQueue<ResponseHeaders>> RECEIVED_INFORMATIONALS =
            AttributeKey.valueOf(HealthCheckedEndpointGroupLongPollingPingTest.class,
                                 "RECEIVED_HEADERS");

    @Nullable
    private volatile BlockingQueue<RequestLogAccess> healthCheckRequestLogs;

    @BeforeEach
    void startServer() {
        healthCheckRequestLogs = null;
    }

    @Test
    void ping() throws Exception {
        final BlockingQueue<RequestLogAccess> healthCheckRequestLogs = new LinkedTransferQueue<>();
        this.healthCheckRequestLogs = healthCheckRequestLogs;

        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(endpoint, "/ping"))) {

            Thread.sleep(3000);

            assertFirstRequest(healthCheckRequestLogs);

            // The second request must be long-polling with informationals.
            final RequestLogAccess longPollingRequestLog = healthCheckRequestLogs.take();

            // There must be two or more '102 Processing' and nothing else.
            final BlockingQueue<ResponseHeaders> receivedInformationals =
                    longPollingRequestLog.context().attr(RECEIVED_INFORMATIONALS);
            assertThat(receivedInformationals).isNotNull();
            for (ResponseHeaders headers : receivedInformationals) {
                assertThat(headers.status()).isEqualTo(HttpStatus.PROCESSING);
            }
            assertThat(receivedInformationals).hasSizeGreaterThanOrEqualTo(2);

            // There must be no other requests sent, because the second request is not finished yet.
            assertThat(healthCheckRequestLogs).isEmpty();

            // Eventually, the endpoint must stay healthy.
            assertThat(endpointGroup.endpoints()).containsExactly(endpoint);
        }
    }

    @Test
    void noPingAfterInitialPing() throws Exception {
        final BlockingQueue<RequestLogAccess> healthCheckRequestLogs = new LinkedTransferQueue<>();
        this.healthCheckRequestLogs = healthCheckRequestLogs;

        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(endpoint, "/no_ping_after_initial_ping"))) {

            Thread.sleep(3000);

            assertFirstRequest(healthCheckRequestLogs);

            // The second request must time out while long-polling.
            final RequestLog longPollingRequestLog = healthCheckRequestLogs.take().whenComplete().join();
            assertThat(longPollingRequestLog.responseCause()).isInstanceOf(ResponseTimeoutException.class);

            // There must be only one '102 Processing' header received.
            final BlockingQueue<ResponseHeaders> receivedInformationals =
                    longPollingRequestLog.context().attr(RECEIVED_INFORMATIONALS);
            assertThat(receivedInformationals).isNotNull();
            for (ResponseHeaders headers : receivedInformationals) {
                assertThat(headers.status()).isEqualTo(HttpStatus.PROCESSING);
            }
            assertThat(receivedInformationals).hasSize(1);

            // Eventually, the endpoint must stay healthy.
            assertThat(endpointGroup.endpoints()).isEmpty();
        }
    }

    @Test
    void noPingAtAll() throws Exception {
        final BlockingQueue<RequestLogAccess> healthCheckRequestLogs = new LinkedTransferQueue<>();
        this.healthCheckRequestLogs = healthCheckRequestLogs;

        final Endpoint endpoint = Endpoint.of("127.0.0.1", server.httpPort());
        try (HealthCheckedEndpointGroup endpointGroup = build(
                HealthCheckedEndpointGroup.builder(endpoint, "/no_ping_at_all"))) {

            Thread.sleep(3000);

            assertFirstRequest(healthCheckRequestLogs);

            // The second request must time out while long-polling.
            final RequestLog longPollingRequestLog = healthCheckRequestLogs.take().whenComplete().join();
            assertThat(longPollingRequestLog.responseCause()).isInstanceOf(ResponseTimeoutException.class);

            // There must be no '102 Processing' headers received.
            final BlockingQueue<ResponseHeaders> receivedInformationals =
                    longPollingRequestLog.context().attr(RECEIVED_INFORMATIONALS);
            assertThat(receivedInformationals).isEmpty();

            // Eventually, the endpoint must stay healthy.
            assertThat(endpointGroup.endpoints()).isEmpty();
        }
    }

    private static void assertFirstRequest(
            BlockingQueue<RequestLogAccess> healthCheckRequestLogs) throws InterruptedException {
        // The first request is always non-long-polling, so there has to be no informationals.
        final RequestLog firstNonLongPollingRequestLog = healthCheckRequestLogs.take().whenComplete().join();
        assertThat(firstNonLongPollingRequestLog.responseHeaders().status()).isEqualTo(HttpStatus.OK);
        assertThat(firstNonLongPollingRequestLog.context().attr(RECEIVED_INFORMATIONALS)).isEmpty();
    }

    private HealthCheckedEndpointGroup build(HealthCheckedEndpointGroupBuilder builder) {
        // Specify backoff explicitly to disable jitter.
        builder.retryBackoff(Backoff.fixed(RETRY_INTERVAL.toMillis()));
        builder.withClientOptions(b -> {
            b.decorator(LoggingClient.newDecorator());
            b.decorator((delegate, ctx, req) -> {
                final Queue<RequestLogAccess> healthCheckRequestLogs = this.healthCheckRequestLogs;
                if (healthCheckRequestLogs == null) {
                    return delegate.execute(ctx, req);
                }

                // Record when health check requests were sent.
                healthCheckRequestLogs.add(ctx.log());

                // Record all the received headers as well.
                ctx.setAttr(RECEIVED_INFORMATIONALS, new LinkedBlockingQueue<>());
                return delegate.execute(ctx, req).mapInformational(headers -> {
                    ctx.attr(RECEIVED_INFORMATIONALS).add(headers);
                    return headers;
                });
            });
            return b;
        });
        return builder.build();
    }
}
