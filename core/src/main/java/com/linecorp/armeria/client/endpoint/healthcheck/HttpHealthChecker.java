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
package com.linecorp.armeria.client.endpoint.healthcheck;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.AsyncCloseable;

import io.netty.util.AsciiString;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;

final class HttpHealthChecker implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpHealthChecker.class);

    private static final AsciiString ARMERIA_LPHC = HttpHeaderNames.of("armeria-lphc");

    private final HealthCheckerContext ctx;
    private final WebClient webClient;
    private final String authority;
    private final String path;
    private final boolean useGet;
    private boolean wasHealthy;
    private int maxLongPollingSeconds;
    private int pingIntervalSeconds;
    @Nullable
    private HttpResponse lastResponse;
    private boolean closed;

    HttpHealthChecker(HealthCheckerContext ctx, String path, boolean useGet) {
        final Endpoint endpoint = ctx.endpoint();
        this.ctx = ctx;
        webClient = WebClient.builder(ctx.protocol(), endpoint)
                             .factory(ctx.clientFactory())
                             .options(ctx.clientConfigurator().apply(ClientOptions.builder()).build())
                             .decorator(ResponseTimeoutUpdater::new)
                             .build();
        authority = endpoint.authority();
        this.path = path;
        this.useGet = useGet;
    }

    void start() {
        check();
    }

    private synchronized void check() {
        if (closed) {
            return;
        }

        final RequestHeaders headers;
        final RequestHeadersBuilder builder =
                RequestHeaders.builder(useGet ? HttpMethod.GET : HttpMethod.HEAD, path)
                              .add(HttpHeaderNames.AUTHORITY, authority);
        if (maxLongPollingSeconds > 0) {
            headers = builder.add(HttpHeaderNames.IF_NONE_MATCH, wasHealthy ? "\"healthy\"" : "\"unhealthy\"")
                             .add(HttpHeaderNames.PREFER, "wait=" + maxLongPollingSeconds)
                             .build();
        } else {
            headers = builder.build();
        }

        try (ClientRequestContextCaptor reqCtxCaptor = Clients.newContextCaptor()) {
            lastResponse = webClient.execute(headers);
            final ClientRequestContext reqCtx = reqCtxCaptor.get();
            lastResponse.subscribe(new HealthCheckResponseSubscriber(reqCtx, lastResponse),
                                   reqCtx.eventLoop(), SubscriptionOption.WITH_POOLED_OBJECTS);
        }
    }

    @Override
    public synchronized CompletableFuture<?> closeAsync() {
        if (lastResponse == null) {
            // Called even before the first request is sent.
            closed = true;
            return CompletableFuture.completedFuture(null);
        }

        if (!closed) {
            closed = true;
            lastResponse.abort();
        }

        return lastResponse.completionFuture().handle((unused1, unused2) -> null);
    }

    private final class ResponseTimeoutUpdater extends SimpleDecoratingHttpClient {
        ResponseTimeoutUpdater(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            if (maxLongPollingSeconds > 0) {
                ctx.extendResponseTimeoutMillis(TimeUnit.SECONDS.toMillis(maxLongPollingSeconds));
            }
            return delegate().execute(ctx, req);
        }
    }

    private class HealthCheckResponseSubscriber implements Subscriber<HttpObject> {

        private final ClientRequestContext reqCtx;
        private final HttpResponse res;
        @SuppressWarnings("NotNullFieldNotInitialized")
        private Subscription subscription;
        private boolean isHealthy;
        private boolean receivedExpectedResponse;
        private boolean updatedHealth;

        @Nullable
        private ScheduledFuture<?> pingCheckFuture;
        private long lastPingTimeNanos;

        HealthCheckResponseSubscriber(ClientRequestContext reqCtx, HttpResponse res) {
            this.reqCtx = reqCtx;
            this.res = res;
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
            maybeSchedulePingCheck();
        }

        @Override
        public void onNext(HttpObject obj) {
            if (closed) {
                subscription.cancel();
                return;
            }

            try {
                if (!(obj instanceof ResponseHeaders)) {
                    ReferenceCountUtil.release(obj);
                    return;
                }

                final ResponseHeaders headers = (ResponseHeaders) obj;
                updateLongPollingSettings(headers);

                final HttpStatusClass statusClass = headers.status().codeClass();
                switch (statusClass) {
                    case INFORMATIONAL:
                        maybeSchedulePingCheck();
                        break;
                    case SERVER_ERROR:
                        receivedExpectedResponse = true;
                        break;
                    case SUCCESS:
                        isHealthy = true;
                        receivedExpectedResponse = true;
                        break;
                    default:
                        if (headers.status() == HttpStatus.NOT_MODIFIED) {
                            isHealthy = wasHealthy;
                            receivedExpectedResponse = true;
                        } else {
                            // Do not use long polling on an unexpected status for safety.
                            maxLongPollingSeconds = 0;
                            logger.warn("{} Unexpected health check response: {}", reqCtx, headers);
                        }
                }
            } finally {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            updateHealth();
        }

        @Override
        public void onComplete() {
            updateHealth();
        }

        private void updateLongPollingSettings(ResponseHeaders headers) {
            final String longPollingSettings = headers.get(ARMERIA_LPHC);
            if (longPollingSettings == null) {
                maxLongPollingSeconds = 0;
                pingIntervalSeconds = 0;
                return;
            }

            final int commaPos = longPollingSettings.indexOf(',');
            int maxLongPollingSeconds = 0;
            int pingIntervalSeconds = 0;
            try {
                maxLongPollingSeconds = Integer.max(
                        0, Integer.parseInt(longPollingSettings.substring(0, commaPos).trim()));
                pingIntervalSeconds = Integer.max(
                        0, Integer.parseInt(longPollingSettings.substring(commaPos + 1).trim()));
            } catch (NumberFormatException e) {
                // Ignore malformed settings.
            }

            HttpHealthChecker.this.maxLongPollingSeconds = maxLongPollingSeconds;
            if (maxLongPollingSeconds > 0 && pingIntervalSeconds < maxLongPollingSeconds) {
                HttpHealthChecker.this.pingIntervalSeconds = pingIntervalSeconds;
            } else {
                HttpHealthChecker.this.pingIntervalSeconds = 0;
            }
        }

        // TODO(trustin): Remove once https://github.com/line/armeria/issues/1063 is fixed.
        private void maybeSchedulePingCheck() {
            lastPingTimeNanos = System.nanoTime();

            if (pingCheckFuture != null) {
                return;
            }

            final int pingIntervalSeconds = HttpHealthChecker.this.pingIntervalSeconds;
            if (pingIntervalSeconds <= 0) {
                return;
            }

            final long pingTimeoutNanos = LongMath.saturatedMultiply(
                    TimeUnit.SECONDS.toNanos(pingIntervalSeconds), 2);

            pingCheckFuture = reqCtx.eventLoop().scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastPingTimeNanos >= pingTimeoutNanos) {
                    // Did not receive a ping on time.
                    res.abort(ResponseTimeoutException.get());
                    isHealthy = false;
                    receivedExpectedResponse = false;
                    updateHealth();
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        private void updateHealth() {
            if (pingCheckFuture != null) {
                pingCheckFuture.cancel(false);
            }

            if (updatedHealth) {
                return;
            }

            updatedHealth = true;

            ctx.updateHealth(isHealthy ? 1 : 0);
            wasHealthy = isHealthy;

            final ScheduledExecutorService executor = ctx.executor();
            try {
                // Send a long polling check immediately if:
                // - Server has long polling enabled.
                // - Server responded with 2xx or 5xx.
                if (maxLongPollingSeconds > 0 && receivedExpectedResponse) {
                    executor.execute(HttpHealthChecker.this::check);
                } else {
                    executor.schedule(HttpHealthChecker.this::check,
                                      ctx.nextDelayMillis(), TimeUnit.MILLISECONDS);
                }
            } catch (RejectedExecutionException ignored) {
                // Can happen if the Endpoint being checked has been disappeared from
                // the delegate EndpointGroup. See HealthCheckedEndpointGroupTest.disappearedEndpoint().
            }
        }
    }
}
