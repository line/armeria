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

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableSet;
import com.google.common.math.LongMath;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerListenerAdapter;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.TransientService;

import io.netty.util.AsciiString;
import io.netty.util.concurrent.ScheduledFuture;

/**
 * An {@link HttpService} that responds with HTTP status {@code "200 OK"} if the server is healthy and can
 * accept requests and HTTP status {@code "503 Service Not Available"} if the server is unhealthy and cannot
 * accept requests. The default behavior is to respond healthy after the server is started and unhealthy
 * after it started to stop.
 *
 * <h3>Long-polling support</h3>
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
 * </ul></p>
 *
 * <p>To wait until a {@link Server} becomes healthy, i.e. wait for the recovery, send an HTTP request with
 * two additional headers:
 * <ul>
 *   <li>{@code If-None-Match: "unhealthy"}</li>
 *   <li>{@code Prefer: wait=<seconds>}</li>
 * </ul></p>
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
public final class HealthCheckService implements HttpService, TransientService<HttpRequest, HttpResponse> {

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
    private final ResponseHeaders notModifiedHeaders;
    private final long maxLongPollingTimeoutMillis;
    private final double longPollingTimeoutJitterRate;
    @Nullable
    private final Consumer<HealthChecker> healthCheckerListener;
    @Nullable
    private final Queue<PendingResponse> pendingHealthyResponses;
    @Nullable
    private final Queue<PendingResponse> pendingUnhealthyResponses;
    @Nullable
    private final HealthCheckUpdateHandler updateHandler;

    @Nullable
    private Server server;
    private boolean serverStopping;

    HealthCheckService(Iterable<HealthChecker> healthCheckers,
                       AggregatedHttpResponse healthyResponse, AggregatedHttpResponse unhealthyResponse,
                       long maxLongPollingTimeoutMillis, double longPollingTimeoutJitterRate,
                       @Nullable HealthCheckUpdateHandler updateHandler) {
        serverHealth = new SettableHealthChecker(false);
        this.healthCheckers = ImmutableSet.<HealthChecker>builder()
                .add(serverHealth).addAll(healthCheckers).build();
        this.updateHandler = updateHandler;

        if (maxLongPollingTimeoutMillis > 0 &&
            this.healthCheckers.stream().allMatch(ListenableHealthChecker.class::isInstance)) {
            this.maxLongPollingTimeoutMillis = maxLongPollingTimeoutMillis;
            this.longPollingTimeoutJitterRate = longPollingTimeoutJitterRate;
            healthCheckerListener = this::onHealthCheckerUpdate;
            pendingHealthyResponses = new ArrayDeque<>();
            pendingUnhealthyResponses = new ArrayDeque<>();
        } else {
            this.maxLongPollingTimeoutMillis = 0;
            this.longPollingTimeoutJitterRate = 0;
            healthCheckerListener = null;
            pendingHealthyResponses = null;
            pendingUnhealthyResponses = null;

            if (maxLongPollingTimeoutMillis > 0) {
                logger.warn("Long-polling support has been disabled for {} " +
                            "because some of the specified {}s are not listenable.",
                            getClass().getSimpleName(), HealthChecker.class.getSimpleName());
            }
        }

        this.healthyResponse = addCommonHeaders(healthyResponse);
        this.unhealthyResponse = addCommonHeaders(unhealthyResponse);
        stoppingResponse = isLongPollingEnabled() ? addCommonHeaders(unhealthyResponse, 0)
                                                  : this.unhealthyResponse;
        notModifiedHeaders = ResponseHeaders.builder()
                                            .add(this.unhealthyResponse.headers())
                                            .status(HttpStatus.NOT_MODIFIED)
                                            .removeAndThen(HttpHeaderNames.CONTENT_LENGTH)
                                            .build();
    }

    private AggregatedHttpResponse addCommonHeaders(AggregatedHttpResponse res) {
        final long maxLongPollingTimeoutSeconds =
                isLongPollingEnabled() ? Math.max(1, maxLongPollingTimeoutMillis / 1000)
                                       : 0;

        return addCommonHeaders(res, maxLongPollingTimeoutSeconds);
    }

    private static AggregatedHttpResponse addCommonHeaders(AggregatedHttpResponse res,
                                                           long maxLongPollingTimeoutSeconds) {
        return AggregatedHttpResponse.of(res.informationals(),
                                         res.headers().toBuilder()
                                            .setLong(ARMERIA_LPHC, maxLongPollingTimeoutSeconds)
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
                    final CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                    final ScheduledFuture<Boolean> timeoutFuture = ctx.eventLoop().schedule(
                            () -> future.complete(HttpResponse.of(notModifiedHeaders)),
                            longPollingTimeoutMillis, TimeUnit.MILLISECONDS);
                    final PendingResponse pendingResponse = new PendingResponse(method, future, timeoutFuture);
                    if (isHealthy) {
                        pendingUnhealthyResponses.add(pendingResponse);
                    } else {
                        pendingHealthyResponses.add(pendingResponse);
                    }

                    updateRequestTimeout(ctx, longPollingTimeoutMillis);
                    return HttpResponse.from(future);
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

        return (long) (Math.min(timeoutMillisHolder.value, maxLongPollingTimeoutMillis) *
                       (1.0 - ThreadLocalRandom.current().nextDouble(longPollingTimeoutJitterRate)));
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
            ctx.setRequestTimeoutMillis(LongMath.saturatedAdd(longPollingTimeoutMillis, requestTimeoutMillis));
        }
    }

    private HttpResponse newResponse(HttpMethod method, boolean isHealthy) {
        final AggregatedHttpResponse aRes;
        if (isHealthy) {
            aRes = healthyResponse;
        } else if (serverStopping) {
            aRes = stoppingResponse;
        } else {
            aRes = unhealthyResponse;
        }

        if (method == HttpMethod.HEAD) {
            return HttpResponse.of(aRes.headers());
        } else {
            return HttpResponse.of(aRes);
        }
    }

    private void onHealthCheckerUpdate(HealthChecker unused) {
        assert healthCheckerListener != null : "healthCheckerListener is null.";
        assert pendingHealthyResponses != null : "pendingHealthyResponses is null.";
        assert pendingUnhealthyResponses != null : "pendingUnhealthyResponses is null.";

        final boolean isHealthy = isHealthy();
        final PendingResponse[] pendingResponses;
        synchronized (healthCheckerListener) {
            final Queue<PendingResponse> queue = isHealthy ? pendingHealthyResponses
                                                           : pendingUnhealthyResponses;
            if (!queue.isEmpty()) {
                pendingResponses = queue.toArray(EMPTY_PENDING_RESPONSES);
                queue.clear();
            } else {
                pendingResponses = EMPTY_PENDING_RESPONSES;
            }
        }

        for (PendingResponse e : pendingResponses) {
            if (e.timeoutFuture.cancel(false)) {
                e.future.complete(newResponse(e.method, isHealthy));
            }
        }
    }

    private static final class PendingResponse {
        final HttpMethod method;
        final CompletableFuture<HttpResponse> future;
        final ScheduledFuture<Boolean> timeoutFuture;

        PendingResponse(HttpMethod method,
                        CompletableFuture<HttpResponse> future,
                        ScheduledFuture<Boolean> timeoutFuture) {
            this.method = method;
            this.future = future;
            this.timeoutFuture = timeoutFuture;
        }
    }

    private static final class LongHolder {
        long value;
    }
}
