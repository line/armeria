/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.server.cors;

import static com.linecorp.armeria.internal.common.ArmeriaHttpUtil.isCorsPreflightRequest;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.server.CorsHeaderUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

/**
 * Decorates an {@link HttpService} to add the
 * <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">Cross-Origin Resource Sharing
 * (CORS)</a> support.
 *
 * @see CorsServiceBuilder
 */
public final class CorsService extends SimpleDecoratingHttpService {

    private static final Logger logger = LoggerFactory.getLogger(CorsService.class);

    /**
     * Returns a new {@link CorsServiceBuilder} with its origin set with {@code "*"} (any origin).
     */
    public static CorsServiceBuilder builderForAnyOrigin() {
        return new CorsServiceBuilder();
    }

    /**
     * Returns a new {@link CorsServiceBuilder} with the specified {@code origins}.
     */
    public static CorsServiceBuilder builder(String... origins) {
        return builder(ImmutableList.copyOf(requireNonNull(origins, "origins")));
    }

    /**
     * Returns a new {@link CorsServiceBuilder} with the specified {@code origins}.
     */
    public static CorsServiceBuilder builder(Iterable<String> origins) {
        requireNonNull(origins, "origins");
        final List<String> copied = ImmutableList.copyOf(origins);
        if (copied.contains(CorsHeaderUtil.ANY_ORIGIN)) {
            if (copied.size() > 1) {
                logger.warn("Any origin (*) has been already included. Other origins ({}) will be ignored.",
                            copied.stream()
                                  .filter(c -> !CorsHeaderUtil.ANY_ORIGIN.equals(c))
                                  .collect(Collectors.joining(",")));
            }
            return builderForAnyOrigin();
        }
        return new CorsServiceBuilder(copied);
    }

    /**
     * Returns a new {@link CorsServiceBuilder} with origins matching the {@code originPredicate}.
     */
    @UnstableApi
    public static CorsServiceBuilder builder(Predicate<? super String> originPredicate) {
        requireNonNull(originPredicate, "originPredicate");
        return new CorsServiceBuilder(originPredicate);
    }

    /**
     * Returns a new {@link CorsServiceBuilder} with origins matching the {@code originRegex}.
     */
    @UnstableApi
    public static CorsServiceBuilder builderForOriginRegex(String originRegex) {
        requireNonNull(originRegex, "originRegex");
        return builderForOriginRegex(Pattern.compile(originRegex));
    }

    /**
     * Returns a new {@link CorsServiceBuilder} with origins matching the {@code originRegex}.
     */
    @UnstableApi
    public static CorsServiceBuilder builderForOriginRegex(Pattern originRegex) {
        return builder(requireNonNull(originRegex, "originRegex").asPredicate());
    }

    private final CorsConfig config;

    CorsService(HttpService delegate, CorsConfig config) {
        super(delegate);
        this.config = requireNonNull(config, "config");
    }

    /**
     * Returns the {@link CorsConfig}.
     */
    public CorsConfig config() {
        return config;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        // check if CORS preflight must be returned, or if
        // we need to forbid access because origin could not be validated
        if (isCorsPreflightRequest(req.headers())) {
            return handleCorsPreflight(ctx, req);
        }
        if (config.isShortCircuit() &&
            config.getPolicy(req.headers().get(HttpHeaderNames.ORIGIN), ctx.routingContext()) == null) {
            return forbidden();
        }

        return unwrap().serve(ctx, req).mapHeaders(headers -> {
            final ResponseHeadersBuilder builder = headers.toBuilder();
            CorsHeaderUtil.setCorsResponseHeaders(ctx, req, builder, config);
            return builder.build();
        });
    }

    /**
     * Handles CORS preflight by setting the appropriate headers.
     *
     * @param req the decoded HTTP request
     */
    private HttpResponse handleCorsPreflight(ServiceRequestContext ctx, HttpRequest req) {
        final ResponseHeadersBuilder headers = ResponseHeaders.builder(HttpStatus.OK);

        final CorsPolicy policy = CorsHeaderUtil.setCorsOrigin(ctx, req, headers, config);
        if (policy != null) {
            policy.setCorsAllowMethods(headers);
            final RequestHeaders requestHeaders = req.headers();
            CorsHeaderUtil.setCorsAllowHeaders(requestHeaders, headers, policy);
            CorsHeaderUtil.setCorsAllowCredentials(headers, policy);
            policy.setCorsMaxAge(headers);
            policy.setCorsPreflightResponseHeaders(headers);
        }

        return HttpResponse.of(headers.build());
    }

    /**
     * Return a "forbidden" response.
     */
    private static HttpResponse forbidden() {
        return HttpResponse.of(HttpStatus.FORBIDDEN);
    }
}
