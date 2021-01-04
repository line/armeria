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

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.common.ArmeriaHttpUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientHttpService;
import com.linecorp.armeria.server.TransientServiceOption;

import io.netty.util.AsciiString;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.ScheduledFuture;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;

/**
 * An {@link HttpService} that responds with HTTP status {@code "200 OK"} if the server is healthy and can
 * accept requests and HTTP status {@code "503 Service Not Available"} if the server is unhealthy and cannot
 * accept requests. The default behavior is to respond healthy after the server is started and unhealthy
 * after it started to stop.
 *
 * <h2>Long-polling support</h2>
 *
 * <p>A client that sends health check requests to this service can send a long-polling request to get notified
 * immediately when a {@link Server} becomes healthy or unhealthy, rather than sending health check requests
 * periodically.</p>
 *
 * <p>To wait until a {@link Server} becomes unhealthy, i.e. wait for the failure, send an HTTP request with
 * two additional headers:
 * <ul>
 *   <li>{@code If-None-Match: "healthy"}</li>
 *   <li>{@code Prefer: wait=<seconds>}
 *     <ul>
 *       <li>e.g. {@code Prefer: wait=60}</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>To wait until a {@link Server} becomes healthy, i.e. wait for the recovery, send an HTTP request with
 * two additional headers:
 * <ul>
 *   <li>{@code If-None-Match: "unhealthy"}</li>
 *   <li>{@code Prefer: wait=<seconds>}</li>
 * </ul>
 *
 * <p>The {@link Server} will wait up to the amount of seconds specified in the {@code "Prefer"} header
 * and respond with {@code "200 OK"}, {@code "503 Service Unavailable"} or {@code "304 Not Modified"}.
 * {@code "304 Not Modifies"} signifies that the healthiness of the {@link Server} did not change.
 * Once the response is received, the client is supposed to send a new long-polling request to continue
 * watching the healthiness of the {@link Server}.</p>
 *
 * <p>All health check responses will contain a {@code "armeria-lphc"} header whose value is the maximum
 * allowed value of the {@code "Prefer: wait=<seconds>"} header. {@code 0} means long polling has been
 * disabled. {@code "lphc"} stands for long-polling health check.</p>
 *
 * @see HealthCheckServiceBuilder
 */
public final class HealthCheckService implements TransientHttpService {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);
    private static final AsciiString ARMERIA_LPHC = HttpHeaderNames.of("armeria-lphc");
    private static final PendingResponse[] EMPTY_PENDING_RESPONSES = new PendingResponse[0];

    /**
     * Returns a newly created {@link HealthCheckService} with the specified {@link HealthChecker}s.
     */
    public static HealthCheckService of(HealthChecker... healthCheckers) {
        return builder().checkers(healthCheckers).build();
    }

    /**
     * Returns a newly created {@link HealthCheckService} with the specified {@link HealthChecker}s.
     */
    public static HealthCheckService of(Iterable<? extends HealthChecker> healthCheckers) {
        return builder().checkers(healthCheckers).build();
    }

    /**
     * Returns a new builder which builds a new {@link HealthCheckService}.
     */
    public static HealthCheckServiceBuilder builder() {
        return new HealthCheckServiceBuilder();
    }

    private final SettableHealthChecker serverHealth;
    private final Set<HealthChecker> healthCheckers;
    private final AggregatedHttpResponse healthyResponse;
    private final AggregatedHttpResponse unhealthyResponse;
    private final AggregatedHttpResponse stoppingResponse;
    private final ResponseHeaders ping;
    private final ResponseHeaders notModifiedHeaders;
    private final long maxLongPollingTimeoutMillis;
    private final double longPollingTimeoutJitterRate;
    private final long pingIntervalMillis;
    @Nullable
    private final Consumer<HealthChecker> healthCheckerListener;
    @Nullable
    @VisibleForTesting
    final Set<PendingResponse> pendingHealthyResponses;
    @Nullable
    @VisibleForTesting
    final Set<PendingResponse> pendingUnhealthyResponses;
    @Nullable
    private final HealthCheckUpdateHandler updateHandler;
    private final List<HealthCheckUpdateListener> updateListeners;
    private final Set<TransientServiceOption> transientServiceOptions;

    @Nullable
    private Server server;
    private boolean serverStopping;

    HealthCheckService(Iterable<HealthChecker> healthCheckers,
                       AggregatedHttpResponse healthyResponse, AggregatedHttpResponse unhealthyResponse,
                       long maxLongPollingTimeoutMillis, double longPollingTimeoutJitterRate,
                       long pingIntervalMillis, @Nullable HealthCheckUpdateHandler updateHandler,
                       Iterable<HealthCheckUpdateListener> updateListeners,
                       Set<TransientServiceOption> transientServiceOptions) {
        serverHealth = new SettableHealthChecker(false);
        this.healthCheckers = ImmutableSet.<HealthChecker>builder()
                .add(serverHealth).addAll(healthCheckers).build();
        this.updateHandler = updateHandler;
        this.updateListeners = ImmutableList.<HealthCheckUpdateListener>builder()
                .addAll(updateListeners).build();
        this.transientServiceOptions = transientServiceOptions;

        if (maxLongPollingTimeoutMillis > 0 &&
            this.healthCheckers.stream().allMatch(ListenableHealthChecker.class::isInstance)) {
            this.maxLongPollingTimeoutMillis = maxLongPollingTimeoutMillis;
            this.longPollingTimeoutJitterRate = longPollingTimeoutJitterRate;
            this.pingIntervalMillis = pingIntervalMillis;
            healthCheckerListener = this::onHealthCheckerUpdate;
            pendingHealthyResponses = new ObjectLinkedOpenHashSet<>();
            pendingUnhealthyResponses = new ObjectLinkedOpenHashSet<>();
        } else {
            this.maxLongPollingTimeoutMillis = 0;
            this.longPollingTimeoutJitterRate = 0;
            this.pingIntervalMillis = 0;
            healthCheckerListener = null;
            pendingHealthyResponses = null;
            pendingUnhealthyResponses = null;

            if (maxLongPollingTimeoutMillis > 0 && logger.isWarnEnabled()) {
                logger.warn("Long-polling support has been disabled " +
                            "because some of the specified {}s do not implement {}: {}",
                            HealthChecker.class.getSimpleName(),
                            ListenableHealthChecker.class.getSimpleName(),
                            this.healthCheckers.stream()
                                               .filter(e -> !(e instanceof ListenableHealthChecker))
                                               .collect(toImmutableList()));
            }
        }

        this.healthyResponse = setCommonHeaders(healthyResponse);
        this.unhealthyResponse = setCommonHeaders(unhealthyResponse);
        stoppingResponse = clearCommonHeaders(unhealthyResponse);
        notModifiedHeaders = ResponseHeaders.builder()
                                            .add(this.unhealthyResponse.headers())
                                            .endOfStream(true)
                                            .status(HttpStatus.NOT_MODIFIED)
                                            .removeAndThen(HttpHeaderNames.CONTENT_LENGTH)
                                            .build();

        ping = setCommonHeaders(ResponseHeaders.of(HttpStatus.PROCESSING));
    }

    private AggregatedHttpResponse setCommonHeaders(AggregatedHttpResponse res) {
        return AggregatedHttpResponse.of(res.informationals(),
                                         setCommonHeaders(res.headers()),
                                         res.content(),
                                         res.trailers().toBuilder()
                                            .removeAndThen(ARMERIA_LPHC)
                                            .build());
    }

    private ResponseHeaders setCommonHeaders(ResponseHeaders headers) {
        final long maxLongPollingTimeoutSeconds;
        final long pingIntervalSeconds;
        if (isLongPollingEnabled()) {
            maxLongPollingTimeoutSeconds = Math.max(1, maxLongPollingTimeoutMillis / 1000);
            pingIntervalSeconds = Math.max(1, pingIntervalMillis / 1000);
        } else {
            maxLongPollingTimeoutSeconds = 0;
            pingIntervalSeconds = 0;
        }

        return setCommonHeaders(headers, maxLongPollingTimeoutSeconds, pingIntervalSeconds);
    }

    private static ResponseHeaders setCommonHeaders(ResponseHeaders headers,
                                                    long maxLongPollingTimeoutSeconds,
                                                    long pingIntervalSeconds) {
        return headers.toBuilder()
                      .set(ARMERIA_LPHC, maxLongPollingTimeoutSeconds + ", " + pingIntervalSeconds)
                      .build();
    }

    private static AggregatedHttpResponse clearCommonHeaders(AggregatedHttpResponse res) {
        return AggregatedHttpResponse.of(res.informationals(),
                                         res.headers().toBuilder()
                                            .removeAndThen(ARMERIA_LPHC)
                                            .build(),
                                         res.content(),
                                         res.trailers().toBuilder()
                                            .removeAndThen(ARMERIA_LPHC)
                                            .build());
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (server != null) {
            if (server != cfg.server()) {
                throw new IllegalStateException("cannot be added to more than one server");
            } else {
                return;
            }
        }

        server = cfg.server();
        server.addListener(new ServerListenerAdapter() {
            @Override
            public void serverStarting(Server server) throws Exception {
                serverStopping = false;
                if (healthCheckerListener != null) {
                    healthCheckers.stream().map(ListenableHealthChecker.class::cast).forEach(c -> {
                        c.addListener(healthCheckerListener);
                    });
                }
            }

            @Override
            public void serverStarted(Server server) {
                serverHealth.setHealthy(true);
            }

            @Override
            public void serverStopping(Server server) {
                serverStopping = true;
                serverHealth.setHealthy(false);
            }

            @Override
            public void serverStopped(Server server) throws Exception {
                if (healthCheckerListener != null) {
                    healthCheckers.stream().map(ListenableHealthChecker.class::cast).forEach(c -> {
                        c.removeListener(healthCheckerListener);
                    });
                }
            }
        });
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final long longPollingTimeoutMillis = getLongPollingTimeoutMillis(req);
        final boolean isHealthy = isHealthy();
        final boolean useLongPolling;
        if (longPollingTimeoutMillis > 0) {
            final String expectedState =
                    Ascii.toLowerCase(req.headers().get(HttpHeaderNames.IF_NONE_MATCH, ""));
            if ("\"healthy\"".equals(expectedState) || "w/\"healthy\"".equals(expectedState)) {
                useLongPolling = isHealthy;
            } else if ("\"unhealthy\"".equals(expectedState) || "w/\"unhealthy\"".equals(expectedState)) {
                useLongPolling = !isHealthy;
            } else {
                useLongPolling = false;
            }
        } else {
            useLongPolling = false;
        }

        final HttpMethod method = ctx.method();
        if (useLongPolling) {
            // Disallow other methods than HEAD/GET for long polling.
            switch (method) {
                case HEAD:
                case GET:
                    break;
                default:
                    throw HttpStatusException.of(HttpStatus.METHOD_NOT_ALLOWED);
            }

            assert healthCheckerListener != null : "healthCheckerListener is null.";
            assert pendingHealthyResponses != null : "pendingHealthyResponses is null.";
            assert pendingUnhealthyResponses != null : "pendingUnhealthyResponses is null.";

            // If healthy, wait until it becomes unhealthy, and vice versa.
            synchronized (healthCheckerListener) {
                final boolean currentHealthiness = isHealthy();
                if (isHealthy == currentHealthiness) {
                    final HttpResponseWriter res = HttpResponse.streaming();

                    final Set<PendingResponse> pendingResponses = isHealthy ? pendingUnhealthyResponses
                                                                            : pendingHealthyResponses;

                    // Send the initial ack (102 Processing) to let the client know that the request
                    // was accepted.
                    res.write(ping);

                    // Send pings (102 Processing) periodically afterwards.
                    final ScheduledFuture<?> pingFuture;
                    if (pingIntervalMillis != 0 && pingIntervalMillis < longPollingTimeoutMillis) {
                        pingFuture = ctx.eventLoop().withoutContext().scheduleWithFixedDelay(
                                new PingTask(res),
                                pingIntervalMillis, pingIntervalMillis, TimeUnit.MILLISECONDS);
                    } else {
                        pingFuture = null;
                    }

                    // Send 304 Not Modified on timeout.
                    final ScheduledFuture<?> timeoutFuture = ctx.eventLoop().withoutContext().schedule(
                            new TimeoutTask(res), longPollingTimeoutMillis, TimeUnit.MILLISECONDS);

                    final PendingResponse pendingResponse =
                            new PendingResponse(method, res, pingFuture, timeoutFuture);
                    pendingResponses.add(pendingResponse);
                    timeoutFuture.addListener((FutureListener<Object>) f -> {
                        synchronized (healthCheckerListener) {
                            pendingResponses.remove(pendingResponse);
                        }
                    });

                    updateRequestTimeout(ctx, longPollingTimeoutMillis);

                    // Cancel the scheduled timeout and ping task if the response is closed,
                    // so that they are removed from the event loop's task queue.
                    res.whenComplete().handle((unused1, unused2) -> {
                        pendingResponse.cancelAllScheduledFutures();
                        return null;
                    });
                    return res;
                } else {
                    // State has been changed before we acquire the lock.
                    // Fall through because there's no need for long polling.
                }
            }
        }

        switch (method) {
            case HEAD:
            case GET:
                return newResponse(method, isHealthy);
            case CONNECT:
            case DELETE:
            case OPTIONS:
            case TRACE:
                return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        assert method == HttpMethod.POST ||
               method == HttpMethod.PUT ||
               method == HttpMethod.PATCH;

        if (updateHandler == null) {
            return HttpResponse.of(HttpStatus.METHOD_NOT_ALLOWED);
        }

        return HttpResponse.from(updateHandler.handle(ctx, req).thenApply(updateResult -> {
            if (updateResult != null) {
                for (HealthCheckUpdateListener updateListener : updateListeners) {
                    try {
                        updateListener.onUpdate(updateResult);
                    } catch (Throwable t) {
                        logger.warn("An error occurred when notifying a Update event", t);
                    }
                }

                switch (updateResult) {
                    case HEALTHY:
                        serverHealth.setHealthy(true);
                        break;
                    case UNHEALTHY:
                        serverHealth.setHealthy(false);
                        break;
                }
            }
            return HttpResponse.of(newResponse(method, isHealthy()));
        }));
    }

    private boolean isHealthy() {
        for (HealthChecker healthChecker : healthCheckers) {
            if (!healthChecker.isHealthy()) {
                return false;
            }
        }
        return true;
    }

    private long getLongPollingTimeoutMillis(HttpRequest req) {
        if (!isLongPollingEnabled()) {
            return 0;
        }

        final String prefer = req.headers().get(HttpHeaderNames.PREFER);
        if (prefer == null) {
            return 0;
        }

        // TODO(trustin): Optimize this once https://github.com/line/armeria/issues/1835 is resolved.
        final LongHolder timeoutMillisHolder = new LongHolder();
        try {
            ArmeriaHttpUtil.parseDirectives(prefer, (name, value) -> {
                if ("wait".equals(name)) {
                    timeoutMillisHolder.value = TimeUnit.SECONDS.toMillis(Long.parseLong(value));
                }
            });
        } catch (NumberFormatException ignored) {
            // Malformed "wait" value.
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        if (timeoutMillisHolder.value <= 0) {
            throw HttpStatusException.of(HttpStatus.BAD_REQUEST);
        }

        final double multiplier;
        if (longPollingTimeoutJitterRate > 0) {
            multiplier = 1.0 - ThreadLocalRandom.current().nextDouble(longPollingTimeoutJitterRate);
        } else {
            multiplier = 1;
        }
        return (long) (Math.min(timeoutMillisHolder.value, maxLongPollingTimeoutMillis) * multiplier);
    }

    private boolean isLongPollingEnabled() {
        return healthCheckerListener != null;
    }

    /**
     * Extends the request timeout by the specified {@code longPollingTimeoutMillis}, because otherwise
     * the client will get {@code "503 Service Unavailable} due to a {@link RequestTimeoutException} before
     * long-polling finishes.
     */
    private static void updateRequestTimeout(ServiceRequestContext ctx, long longPollingTimeoutMillis) {
        final long requestTimeoutMillis = ctx.requestTimeoutMillis();
        if (requestTimeoutMillis > 0) {
            ctx.setRequestTimeoutMillis(TimeoutMode.EXTEND, longPollingTimeoutMillis);
        }
    }

    private HttpResponse newResponse(HttpMethod method, boolean isHealthy) {
        final AggregatedHttpResponse aRes = getResponse(isHealthy);

        if (method == HttpMethod.HEAD) {
            return HttpResponse.of(aRes.headers());
        } else {
            return aRes.toHttpResponse();
        }
    }

    private AggregatedHttpResponse getResponse(boolean isHealthy) {
        if (isHealthy) {
            return healthyResponse;
        }

        if (serverStopping) {
            return stoppingResponse;
        }

        return unhealthyResponse;
    }

    private void onHealthCheckerUpdate(HealthChecker unused) {
        assert healthCheckerListener != null : "healthCheckerListener is null.";
        assert pendingHealthyResponses != null : "pendingHealthyResponses is null.";
        assert pendingUnhealthyResponses != null : "pendingUnhealthyResponses is null.";

        final boolean isHealthy = isHealthy();
        final PendingResponse[] pendingResponses;
        synchronized (healthCheckerListener) {
            final Set<PendingResponse> set = isHealthy ? pendingHealthyResponses
                                                       : pendingUnhealthyResponses;
            if (!set.isEmpty()) {
                pendingResponses = set.toArray(EMPTY_PENDING_RESPONSES);
                set.clear();
            } else {
                pendingResponses = EMPTY_PENDING_RESPONSES;
            }
        }

        final AggregatedHttpResponse res = getResponse(isHealthy);
        for (PendingResponse e : pendingResponses) {
            if (e.cancelAllScheduledFutures()) {
                if (e.method == HttpMethod.HEAD) {
                    if (e.res.tryWrite(res.headers())) {
                        e.res.close();
                    }
                } else {
                    e.res.close(res);
                }
            }
        }
    }

    @Override
    public Set<TransientServiceOption> transientServiceOptions() {
        return transientServiceOptions;
    }

    private static final class PendingResponse {
        final HttpMethod method;
        final HttpResponseWriter res;
        @Nullable
        private final ScheduledFuture<?> pingFuture;
        private final ScheduledFuture<?> timeoutFuture;

        PendingResponse(HttpMethod method,
                        HttpResponseWriter res,
                        @Nullable ScheduledFuture<?> pingFuture,
                        ScheduledFuture<?> timeoutFuture) {
            this.method = method;
            this.res = res;
            this.pingFuture = pingFuture;
            this.timeoutFuture = timeoutFuture;
        }

        boolean cancelAllScheduledFutures() {
            if (pingFuture != null) {
                pingFuture.cancel(false);
            }
            return timeoutFuture.cancel(false);
        }
    }

    private class PingTask implements Runnable {

        private final HttpResponseWriter res;
        private int pendingPings;

        PingTask(HttpResponseWriter res) {
            this.res = res;
        }

        @Override
        public void run() {
            if (pendingPings < 5) {
                if (res.tryWrite(ping)) {
                    ++pendingPings;
                    res.whenConsumed().thenRun(() -> pendingPings--);
                }
            } else {
                // Do not send a ping if the client is not reading it.
            }
        }
    }

    private class TimeoutTask implements Runnable {
        private final HttpResponseWriter res;

        TimeoutTask(HttpResponseWriter res) {
            this.res = res;
        }

        @Override
        public void run() {
            if (res.tryWrite(notModifiedHeaders)) {
                res.close();
            }
        }
    }

    private static final class LongHolder {
        long value;
    }
}
