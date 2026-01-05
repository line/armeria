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
 *
 */

package com.linecorp.armeria.server.athenz;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.athenz.ZtsBaseClient;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.athenz.TokenType;
import com.linecorp.armeria.common.metric.MeterIdPrefix;
import com.linecorp.armeria.common.metric.MoreMeters;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.athenz.MinifiedAuthZpeClient.AccessCheckStatus;
import com.linecorp.armeria.server.athenz.resource.AthenzResourceNotFoundException;
import com.linecorp.armeria.server.athenz.resource.AthenzResourceProvider;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Decorates an {@link HttpService} to check access permissions using Athenz policies.
 *
 * <p>This decorator supports both static and dynamic resource configurations:
 * <ul>
 *   <li><strong>Static Resource</strong>: Use when the resource name is fixed
 *   (e.g., "users", "admin")</li>
 *   <li><strong>Dynamic Resource</strong>: Use when the resource varies per request
 *   (e.g., extracted from path, headers, or body)</li>
 * </ul>
 *
 * <p>The decorator performs the following steps for each request:
 * <ol>
 *   <li>Extracts authentication token from request headers</li>
 *   <li>Resolves the Athenz resource (either static or dynamic)</li>
 *   <li>Checks access permissions using the Athenz policy</li>
 *   <li>Either allows the request to proceed or returns an error response</li>
 * </ol>
 *
 * <p><strong>Static Resource Example:</strong>
 * <pre>{@code
 * ZtsBaseClient ztsBaseClient =
 *   ZtsBaseClient.builder("https://athenz.example.com:8443/zts/v1")
 *                .keyPair("/var/lib/athenz/service.key.pem", "/var/lib/athenz/service.cert.pem")
 *                .build();
 *
 * ServerBuilder sb = Server.builder();
 * sb.decorator("/users",
 *              AthenzService.builder(ztsBaseClient)
 *                           .action("read")
 *                           .resource("users")
 *                           .newDecorator());
 * }</pre>
 *
 * <p><strong>Dynamic Resource Example (Path-based):</strong>
 * <pre>{@code
 * sb.decorator("/admin/users/:userId",
 *              AthenzService.builder(ztsBaseClient)
 *                           .action("read")
 *                           .resourceProvider(AthenzResourceProvider.ofPath())
 *                           .newDecorator());
 * }</pre>
 *
 * <p><strong>Dynamic Resource Example (Header-based):</strong>
 * <pre>{@code
 * sb.decorator("/api/resources",
 *              AthenzService.builder(ztsBaseClient)
 *                           .action("write")
 *                           .resourceProvider(AthenzResourceProvider.ofHeader("X-Resource-Id"))
 *                           .newDecorator());
 * }</pre>
 *
 * <p><strong>Error Handling:</strong>
 * <ul>
 *   <li>{@link HttpStatus#UNAUTHORIZED} (401) - Returned when the token is missing, access is denied,
 *       or the Athenz resource cannot be resolved.</li>
 *   <li>{@link HttpStatus#INTERNAL_SERVER_ERROR} (500) - Returned when an exception occurs while
 *       resolving the Athenz resource.</li>
 * </ul>
 *
 * @see AthenzServiceBuilder
 * @see AthenzResourceProvider
 */
@UnstableApi
public final class AthenzService extends SimpleDecoratingHttpService {

    /**
     * Returns a new {@link AthenzServiceBuilder} with the specified {@link ZtsBaseClient}.
     */
    public static AthenzServiceBuilder builder(ZtsBaseClient ztsBaseClient) {
        requireNonNull(ztsBaseClient, "ztsBaseClient");
        return new AthenzServiceBuilder(ztsBaseClient);
    }

    private final MinifiedAuthZpeClient authZpeClient;
    private final AthenzResourceProvider athenzResourceProvider;
    private final String athenzAction;
    private final List<TokenType> tokenTypes;
    private final MeterIdPrefix meterIdPrefix;
    private final String resourceTagValue;

    @Nullable
    private Timer allowedTimer;
    @Nullable
    private Timer deniedTimer;

    AthenzService(HttpService delegate, MinifiedAuthZpeClient authZpeClient,
                  AthenzResourceProvider athenzResourceProvider, String athenzAction,
                  List<TokenType> tokenTypes, MeterIdPrefix meterIdPrefix, String resourceTagValue) {
        super(delegate);

        this.authZpeClient = authZpeClient;
        this.athenzResourceProvider = athenzResourceProvider;
        this.athenzAction = athenzAction;
        this.tokenTypes = tokenTypes;
        this.meterIdPrefix = meterIdPrefix;
        this.resourceTagValue = resourceTagValue;
    }

    AthenzService(HttpService delegate, MinifiedAuthZpeClient authZpeClient, String athenzResource,
                  String athenzAction, List<TokenType> tokenTypes, MeterIdPrefix meterIdPrefix) {
        this(delegate, authZpeClient, (ctx, req) -> UnmodifiableFuture.completedFuture(athenzResource),
                athenzAction, tokenTypes, meterIdPrefix, athenzResource);
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        super.serviceAdded(cfg);
        final MeterRegistry meterRegistry = cfg.server().meterRegistry();
        final String name = meterIdPrefix.name("token.authorization");
        allowedTimer = MoreMeters.newTimer(meterRegistry, name,
                                           meterIdPrefix.tags("result", "allowed",
                                                              "resource", resourceTagValue,
                                                              "action", athenzAction));
        deniedTimer = MoreMeters.newTimer(meterRegistry, name,
                                          meterIdPrefix.tags("result", "denied",
                                                             "resource", resourceTagValue,
                                                             "action", athenzAction));
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        final long startNanos = System.nanoTime();

        final String token = extractToken(req.headers());
        if (token == null) {
            assert deniedTimer != null;
            deniedTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.PLAIN_TEXT, "Missing token");
        }

        try {
            final CompletableFuture<String> resourceFuture = athenzResourceProvider.provide(ctx, req);
            final CompletableFuture<HttpResponse> future = resourceFuture
                    .thenApplyAsync(
                            athenzResource -> {
                                if (athenzResource == null || athenzResource.isEmpty()) {
                                    throw new AthenzResourceNotFoundException(
                                            "Athenz resource could not be resolved.");
                                }
                                final AccessCheckStatus status = authZpeClient.allowAccess(token,
                                                                                           athenzResource,
                                                                                           athenzAction);
                                final long elapsedNanos = System.nanoTime() - startNanos;
                                if (status == AccessCheckStatus.ALLOW) {
                                    assert allowedTimer != null;
                                    allowedTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
                                    try {
                                        return unwrap().serve(ctx, req);
                                    } catch (Exception e) {
                                        return Exceptions.throwUnsafely(e);
                                    }
                                } else {
                                    assert deniedTimer != null;
                                    deniedTimer.record(elapsedNanos, TimeUnit.NANOSECONDS);
                                    return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.PLAIN_TEXT,
                                                           status.toString());
                                }
                            },
                            ctx.blockingTaskExecutor())
                    .exceptionally(cause -> {
                        final Throwable unwrapped = Exceptions.peel(cause);
                        assert deniedTimer != null;
                        deniedTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
                        return createErrorResponse(unwrapped);
                    });
            return HttpResponse.of(future);
        } catch (Exception e) {
            assert deniedTimer != null;
            deniedTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
            return createErrorResponse(e);
        }
    }

    @Nullable
    private String extractToken(RequestHeaders headers) {
        for (TokenType tokenType : tokenTypes) {
            final String token = headers.get(tokenType.headerName(), "");
            if (token.isEmpty()) {
                continue;
            }
            return token;
        }
        return null;
    }

    private static HttpResponse createErrorResponse(Throwable cause) {
        if (cause instanceof AthenzResourceNotFoundException) {
            return HttpResponse.of(HttpStatus.UNAUTHORIZED, MediaType.PLAIN_TEXT,
                                   "Resource could not be resolved: " + cause.getMessage());
        } else {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT,
                                   "Exception occurred while resolving resource: " + cause.getMessage());
        }
    }
}
