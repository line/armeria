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
package com.linecorp.armeria.internal.client.endpoint.healthcheck;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.SimpleDecoratingHttpClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerContext;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckerParams;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;

public final class HttpHealthChecker implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HttpHealthChecker.class);

    private static final AsciiString ARMERIA_LPHC = HttpHeaderNames.of("armeria-lphc");

    private final ReentrantLock lock = new ReentrantShortLock();
    private final HealthCheckerContext ctx;
    private boolean wasHealthy;
    private int maxLongPollingSeconds;
    private int pingIntervalSeconds;
    @Nullable
    private HttpResponse lastResponse;
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync);

    public HttpHealthChecker(HealthCheckerContext ctx) {
        this.ctx = ctx;
    }

    public void start() {
        check();
    }

    private void check() {
        lock();
        try {
            if (closeable.isClosing()) {
                return;
            }

            final HealthCheckerParams params = ctx.paramsFactory().get();
            final Endpoint endpoint = params.endpoint();
            final WebClient webClient = WebClient.builder(ctx.protocol(), endpoint)
                                                 .options(ctx.clientOptions())
                                                 .decorator(ResponseTimeoutUpdater::new)
                                                 .build();
            final String host = params.host();
            final String authority = host != null ? host : endpoint.authority();
            final RequestHeadersBuilder builder =
                    RequestHeaders.builder(params.httpMethod(), params.path())
                                  .authority(authority);
            final RequestHeaders headers;
            if (maxLongPollingSeconds > 0) {
                headers = builder.add(HttpHeaderNames.IF_NONE_MATCH,
                                      wasHealthy ? "\"healthy\"" : "\"unhealthy\"")
                                 .add(HttpHeaderNames.PREFER, "wait=" + maxLongPollingSeconds)
                                 .build();
            } else {
                headers = builder.build();
            }

            try (ClientRequestContextCaptor reqCtxCaptor = Clients.newContextCaptor()) {
                lastResponse = webClient.execute(headers);
                final ClientRequestContext reqCtx = reqCtxCaptor.get();
                lastResponse.subscribe(new HealthCheckResponseSubscriber(reqCtx, lastResponse),
                                       reqCtx.eventLoop().withoutContext(),
                                       SubscriptionOption.WITH_POOLED_OBJECTS);
            }
        } catch (Exception e) {
            logger.warn("Unexpected exception while sending a health check request, e: ", e);
        } finally {
            unlock();
        }
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    private synchronized void closeAsync(CompletableFuture<?> future) {
        lock();
        try {
            if (lastResponse == null) {
                // Called even before the first request is sent.
                future.complete(null);
            } else {
                lastResponse.abort();
                lastResponse.whenComplete().handle((unused1, unused2) -> future.complete(null));
            }
        } finally {
            unlock();
        }
    }

    @Override
    public void close() {
        closeable.close();
    }

    private final class ResponseTimeoutUpdater extends SimpleDecoratingHttpClient {
        ResponseTimeoutUpdater(HttpClient delegate) {
            super(delegate);
        }

        @Override
        public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
            if (maxLongPollingSeconds > 0) {
                ctx.setResponseTimeoutMillis(TimeoutMode.EXTEND,
                                             TimeUnit.SECONDS.toMillis(maxLongPollingSeconds));
            }
            return unwrap().execute(ctx, req);
        }
    }

    private class HealthCheckResponseSubscriber implements Subscriber<HttpObject> {

        private final ClientRequestContext reqCtx;
        private final HttpResponse res;
        @SuppressWarnings("NotNullFieldNotInitialized")
        private Subscription subscription;
        @Nullable
        private ResponseHeaders responseHeaders;
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
            if (closeable.isClosing()) {
                subscription.cancel();
                return;
            }

            try {
                if (!(obj instanceof ResponseHeaders)) {
                    PooledObjects.close(obj);
                    return;
                }

                final ResponseHeaders headers = (ResponseHeaders) obj;
                responseHeaders = headers;
                updateLongPollingSettings(headers);

                final HttpStatus status = headers.status();
                final HttpStatusClass statusClass = status.codeClass();
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
                        if (status == HttpStatus.NOT_MODIFIED) {
                            isHealthy = wasHealthy;
                            receivedExpectedResponse = true;
                        } else {
                            // Do not use long polling on an unexpected status for safety.
                            maxLongPollingSeconds = 0;

                            if (statusClass == HttpStatusClass.CLIENT_ERROR) {
                                logger.warn("{} Unexpected 4xx health check response: {} A 4xx response " +
                                            "generally indicates a misconfiguration of the client. " +
                                            "Did you happen to forget to configure the {}'s client options?",
                                            reqCtx, headers, HealthCheckedEndpointGroup.class.getSimpleName());
                            } else {
                                logger.warn("{} Unexpected health check response: {}", reqCtx, headers);
                            }
                        }
                }
            } finally {
                subscription.request(1);
            }
        }

        @Override
        public void onError(Throwable t) {
            updateHealth(t);
        }

        @Override
        public void onComplete() {
            updateHealth(null);
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
            } catch (Exception e) {
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

            final long pingTimeoutNanos = TimeUnit.SECONDS.toNanos(pingIntervalSeconds) * 2;
            pingCheckFuture = reqCtx.eventLoop().withoutContext().scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastPingTimeNanos >= pingTimeoutNanos) {
                    // Did not receive a ping on time.
                    final ResponseTimeoutException cause = ResponseTimeoutException.get();
                    res.abort(cause);
                    isHealthy = false;
                    receivedExpectedResponse = false;
                    updateHealth(cause);
                }
            }, 1, 1, TimeUnit.SECONDS);
        }

        private void updateHealth(@Nullable Throwable cause) {
            if (pingCheckFuture != null) {
                pingCheckFuture.cancel(false);
            }

            if (closeable.isClosing() || updatedHealth) {
                return;
            }

            updatedHealth = true;

            ctx.updateHealth(isHealthy ? 1 : 0, reqCtx, responseHeaders, cause);
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

    private void lock() {
        lock.lock();
    }

    private void unlock() {
        lock.unlock();
    }
}
