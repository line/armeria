/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.outlier.OutlierDetection;
import com.linecorp.armeria.common.outlier.OutlierDetectionDecision;
import com.linecorp.armeria.common.outlier.OutlierRule;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.HttpSession;
import com.linecorp.armeria.internal.testing.AnticipatedException;
import com.linecorp.armeria.internal.testing.TestTicker;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ConnectionOutlierDetectionTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service("/error", (ctx, req) -> HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR));
            sb.service("/unavailable", (ctx, req) -> HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE));
            sb.service("/ok", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
            sb.service("/stream", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @Test
    void inactiveConnectionWhenExceedingThreshold() throws Exception {
        final OutlierRule detectingRule =
                OutlierRule.builder()
                           .onStatus(HttpStatus.SERVICE_UNAVAILABLE, OutlierDetectionDecision.FATAL)
                           .onStatus(HttpStatus.INTERNAL_SERVER_ERROR,
                                              OutlierDetectionDecision.FAILURE)
                           .build();
        final int minimumRequestThreshold = 5;
        final double failureRateThreshold = 0.8;
        final TestTicker ticker = new TestTicker();
        final Duration slidingWindow = Duration.ofSeconds(2);
        final Duration updateInterval = Duration.ofMillis(200);
        final OutlierDetection detection =
                OutlierDetection.builder(detectingRule)
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final CountingConnectionPoolListener poolListener =
                new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionOutlierDetection(detection)
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            for (int i = 0; i < 5; i++) {
                sendRequestAndCheckOutlier(client, "/ok", false);
            }
            for (int i = 0; i < 5; i++) {
                sendRequestAndCheckOutlier(client, "/error", false);
            }

            ticker.advance(slidingWindow);
            // The failure rate is 5 / 10 = 0.5, which is less than the threshold.
            sendRequestAndCheckOutlier(client, "/error", false);
            sendRequestAndCheckOutlier(client, "/ok", false);

            // Reset the counter.
            ticker.advance(slidingWindow);
            for (int i = 0; i < 1; i++) {
                sendRequestAndCheckOutlier(client, "/ok", false);
            }
            ticker.advance(updateInterval);
            for (int i = 0; i < 9; i++) {
                sendRequestAndCheckOutlier(client, "/error", false);
            }

            assertThat(poolListener.opened()).isOne();
            ticker.advance(updateInterval);
            sendRequestAndCheckOutlier(client, "/ok", true);
            assertThat(poolListener.opened()).isOne();

            // A new connection should be opened because the previous connection is marked as an outlier.
            sendRequestAndCheckOutlier(client, "/ok", false);
            assertThat(poolListener.opened()).isEqualTo(2);
        }
    }

    @Test
    void immediatelyInactiveConnectionOnFatal() throws Exception {
        final OutlierRule detectingRule =
                OutlierRule.builder()
                           .onStatus(HttpStatus.SERVICE_UNAVAILABLE, OutlierDetectionDecision.FATAL)
                           .onStatus(HttpStatus.INTERNAL_SERVER_ERROR,
                                              OutlierDetectionDecision.FAILURE)
                           .build();
        final int minimumRequestThreshold = 5;
        final double failureRateThreshold = 0.8;
        final TestTicker ticker = new TestTicker();
        final Duration slidingWindow = Duration.ofSeconds(2);
        final Duration updateInterval = Duration.ofMillis(200);
        final OutlierDetection detection =
                OutlierDetection.builder(detectingRule)
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final CountingConnectionPoolListener poolListener =
                new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionOutlierDetection(detection)
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {
            final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                      .factory(factory)
                                                      .build()
                                                      .blocking();
            sendRequestAndCheckOutlier(client, "/ok", false);
            assertThat(poolListener.opened()).isOne();
            sendRequestAndCheckOutlier(client, "/unavailable", true);
            assertThat(poolListener.opened()).isOne();
            sendRequestAndCheckOutlier(client, "/unavailable", true);
            assertThat(poolListener.opened()).isEqualTo(2);
        }
    }

    @Test
    void immediatelyInactiveConnectionOnException() throws Exception {
        final OutlierRule detectingRule =
                OutlierRule.builder()
                           .onException(AbortedStreamException.class, OutlierDetectionDecision.FAILURE)
                           .onException(AnticipatedException.class, OutlierDetectionDecision.FATAL)
                           .build();
        final int minimumRequestThreshold = 5;
        final double failureRateThreshold = 0.8;
        final TestTicker ticker = new TestTicker();
        final Duration slidingWindow = Duration.ofSeconds(2);
        final Duration updateInterval = Duration.ofMillis(200);
        final OutlierDetection detection =
                OutlierDetection.builder(detectingRule)
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final CountingConnectionPoolListener poolListener =
                new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionOutlierDetection(detection)
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {
            final WebClient client = WebClient.builder(server.httpUri())
                                                      .factory(factory)
                                                      .build();
            abortResponseAndCheckOutlier(client, "/stream", AbortedStreamException.get(), false);
            assertThat(poolListener.opened()).isOne();
            abortResponseAndCheckOutlier(client, "/stream", new AnticipatedException(), true);
            assertThat(poolListener.opened()).isOne();
            abortResponseAndCheckOutlier(client, "/stream", new AnticipatedException(), true);
            assertThat(poolListener.opened()).isEqualTo(2);
        }
    }

    @Test
    void inactiveConnectionWhenExceedingThresholdByException() throws Exception {
        final OutlierRule detectingRule =
                OutlierRule.builder()
                           .onException(AbortedStreamException.class, OutlierDetectionDecision.FAILURE)
                           .onException(AnticipatedException.class, OutlierDetectionDecision.FATAL)
                           .build();
        final int minimumRequestThreshold = 5;
        final double failureRateThreshold = 0.8;
        final TestTicker ticker = new TestTicker();
        final Duration slidingWindow = Duration.ofSeconds(2);
        final Duration updateInterval = Duration.ofMillis(200);
        final OutlierDetection detection =
                OutlierDetection.builder(detectingRule)
                                .counterSlidingWindow(slidingWindow)
                                .counterUpdateInterval(updateInterval)
                                .failureRateThreshold(failureRateThreshold)
                                .minimumRequestThreshold(minimumRequestThreshold)
                                .ticker(ticker)
                                .build();
        final CountingConnectionPoolListener poolListener =
                new CountingConnectionPoolListener();
        try (ClientFactory factory = ClientFactory.builder()
                                                  .connectionOutlierDetection(detection)
                                                  .connectionPoolListener(poolListener)
                                                  .build()) {
            final WebClient client = WebClient.builder(server.httpUri())
                                              .factory(factory)
                                              .build();

            final BlockingWebClient blocking = client.blocking();
            for (int i = 0; i < 5; i++) {
                sendRequestAndCheckOutlier(blocking, "/ok", false);
            }
            for (int i = 0; i < 5; i++) {
                abortResponseAndCheckOutlier(client, "/stream", AbortedStreamException.get(), false);
            }

            ticker.advance(slidingWindow);
            // The failure rate is 5 / 10 = 0.5, which is less than the threshold.
            abortResponseAndCheckOutlier(client, "/stream", AbortedStreamException.get(), false);
            sendRequestAndCheckOutlier(blocking, "/ok", false);

            // Reset the counter.
            ticker.advance(slidingWindow);
            for (int i = 0; i < 1; i++) {
                sendRequestAndCheckOutlier(blocking, "/ok", false);
            }
            ticker.advance(updateInterval);
            for (int i = 0; i < 9; i++) {
                abortResponseAndCheckOutlier(client, "/stream", AbortedStreamException.get(), false);
            }

            assertThat(poolListener.opened()).isOne();
            ticker.advance(updateInterval);
            sendRequestAndCheckOutlier(blocking, "/ok", true);
            assertThat(poolListener.opened()).isOne();

            // A new connection should be opened because the previous connection is marked as an outlier.
            sendRequestAndCheckOutlier(blocking, "/ok", false);
            assertThat(poolListener.opened()).isEqualTo(2);
        }
    }

    private static void sendRequestAndCheckOutlier(BlockingWebClient client, String path, boolean isOutlier)
            throws Exception {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            client.get(path);
            final ClientRequestContext ctx = captor.get();
            final RequestLog log = ctx.log().whenComplete().join();
            // Wait for the outlier detection to be performed which is done in the `.whenComplete()` hook.
            Thread.sleep(10);
            final HttpSession session = HttpSession.get(log.channel());
            assertThat(session.isAcquirable()).isNotEqualTo(isOutlier);
        }
    }

    private static void abortResponseAndCheckOutlier(WebClient client, String path, Throwable exception,
                                                     boolean isOutlier)
            throws Exception {
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final HttpResponse response = client.get(path);
            final ClientRequestContext ctx = captor.get();
            ctx.log().whenAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME).join();
            response.abort(exception);
            final RequestLog log = ctx.log().whenComplete().join();
            // Wait for the outlier detection to be performed which is done in the `.whenComplete()` hook.
            Thread.sleep(10);
            final HttpSession session = HttpSession.get(log.channel());
            assertThat(session.isAcquirable()).isNotEqualTo(isOutlier);
        }
    }
}
