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
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
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

    static final String ANY_ORIGIN = "*";
    static final String NULL_ORIGIN = "null";

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
        if (copied.contains(ANY_ORIGIN)) {
            if (copied.size() > 1) {
                logger.warn("Any origin (*) has been already included. Other origins ({}) will be ignored.",
                            copied.stream()
                                  .filter(c -> !ANY_ORIGIN.equals(c))
                                  .collect(Collectors.joining(",")));
            }
            return builderForAnyOrigin();
        }
        return new CorsServiceBuilder(copied);
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
        if (isCorsPreflightRequest(req)) {
            return handleCorsPreflight(ctx, req);
        }
        if (config.isShortCircuit() &&
            config.getPolicy(req.headers().get(HttpHeaderNames.ORIGIN), ctx.routingContext()) == null) {
            return forbidden();
        }

        return unwrap().serve(ctx, req).mapHeaders(headers -> {
            final ResponseHeadersBuilder builder = headers.toBuilder();
            setCorsResponseHeaders(ctx, req, builder);
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
        final CorsPolicy policy = setCorsOrigin(ctx, req, headers);
        if (policy != null) {
            policy.setCorsAllowMethods(headers);
            policy.setCorsAllowHeaders(headers);
            policy.setCorsAllowCredentials(headers);
            policy.setCorsMaxAge(headers);
            policy.setCorsPreflightResponseHeaders(headers);
        }

        return HttpResponse.of(headers.build());
    }

    /**
     * Emit CORS headers if origin was found.
     *
     * @param req the HTTP request with the CORS info
     * @param headers the headers to modify
     */
    private void setCorsResponseHeaders(ServiceRequestContext ctx, HttpRequest req,
                                        ResponseHeadersBuilder headers) {
        final CorsPolicy policy = setCorsOrigin(ctx, req, headers);
        if (policy != null) {
            policy.setCorsAllowCredentials(headers);
            policy.setCorsAllowHeaders(headers);
            policy.setCorsExposeHeaders(headers);
        }
    }

    /**
     * Return a "forbidden" response.
     */
    private static HttpResponse forbidden() {
        return HttpResponse.of(HttpStatus.FORBIDDEN);
    }

    /**
     * Sets origin header according to the given CORS configuration and HTTP request.
     *
     * @param request the HTTP request
     * @param headers the HTTP headers to modify
     *
     * @return {@code policy} if CORS configuration matches, otherwise {@code null}
     */
    @Nullable
    private CorsPolicy setCorsOrigin(ServiceRequestContext ctx, HttpRequest request,
                                     ResponseHeadersBuilder headers) {

        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            final CorsPolicy policy = config.getPolicy(origin, ctx.routingContext());
            if (policy == null) {
                logger.debug(
                        "{} There is no CORS policy configured for the request origin '{}' and the path '{}'.",
                        ctx, origin, ctx.path());
                return null;
            }
            if (NULL_ORIGIN.equals(origin)) {
                setCorsNullOrigin(headers);
                return policy;
            }
            if (config.isAnyOriginSupported()) {
                if (policy.isCredentialsAllowed()) {
                    echoCorsRequestOrigin(request, headers);
                    setCorsVaryHeader(headers);
                } else {
                    setCorsAnyOrigin(headers);
                }
                return policy;
            }
            setCorsOrigin(headers, origin);
            setCorsVaryHeader(headers);
            return policy;
        }
        return null;
    }

    private static void setCorsOrigin(ResponseHeadersBuilder headers, String origin) {
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }

    private static void echoCorsRequestOrigin(HttpRequest request, ResponseHeadersBuilder headers) {
        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            setCorsOrigin(headers, origin);
        }
    }

    private static void setCorsVaryHeader(ResponseHeadersBuilder headers) {
        headers.set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN.toString());
    }

    private static void setCorsAnyOrigin(ResponseHeadersBuilder headers) {
        setCorsOrigin(headers, ANY_ORIGIN);
    }

    private static void setCorsNullOrigin(ResponseHeadersBuilder headers) {
        setCorsOrigin(headers, NULL_ORIGIN);
    }
}
