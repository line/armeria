/*
 * Copyright 2024 LY Corporation
 *
 *         LY Corporation licenses this file to you under the Apache License,
 *         version 2.0 (the "License"); you may not use this file except in compliance
 *         with the License. You may obtain a copy of the License at:
 *
 *         https://www.apache.org/licenses/LICENSE-2.0
 *
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *         WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *         License for the specific language governing permissions and limitations
 *         under the License.
 */
package com.linecorp.armeria.internal.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.cors.CorsConfig;
import com.linecorp.armeria.server.cors.CorsPolicy;
import com.linecorp.armeria.server.cors.CorsService;

/**
 * utility class related to CORS headers.
 */
public final class CorsHeaderUtil {

    private static final Logger logger = LoggerFactory.getLogger(CorsHeaderUtil.class);

    public static final String ANY_ORIGIN = "*";
    public static final String NULL_ORIGIN = "null";

    private CorsHeaderUtil() {
    }

    /**
     * Emit CORS headers if origin was found.
     *
     * @param req     the HTTP request with the CORS info
     * @param headers the headers to modify
     */
    public static void setCorsResponseHeaders(ServiceRequestContext ctx, HttpRequest req,
                                              ResponseHeadersBuilder headers, CorsService corsService) {
        final CorsPolicy policy = setCorsOrigin(ctx, req, headers, corsService.config(), logger);
        if (policy != null) {
            setCorsAllowCredentials(headers, policy);
            setCorsAllowHeaders(req.headers(), headers, policy);
            setCorsExposeHeaders(headers, policy);
        }
    }

    public static void setCorsAllowCredentials(ResponseHeadersBuilder headers, CorsPolicy policy) {
        // The string "*" cannot be used for a resource that supports credentials.
        // https://www.w3.org/TR/cors/#resource-requests
        if (policy.isCredentialsAllowed() &&
            !ANY_ORIGIN.equals(headers.get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    public static void setCorsExposeHeaders(ResponseHeadersBuilder headers, CorsPolicy corsPolicy) {
        if (corsPolicy.getExposedHeaders().isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, corsPolicy.joinExposedHeaders());
    }

    public static void copyCorsAllowHeaders(RequestHeaders requestHeaders, ResponseHeadersBuilder headers) {
        final String header = requestHeaders.get(HttpHeaderNames.ACCESS_CONTROL_REQUEST_HEADERS);
        if (Strings.isNullOrEmpty(header)) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, header);
    }

    public static void setCorsAllowHeaders(RequestHeaders requestHeaders, ResponseHeadersBuilder headers,
                                           CorsPolicy corsPolicy) {
        if (corsPolicy.isAllowAllRequestHeaders()) {
            copyCorsAllowHeaders(requestHeaders, headers);
            return;
        }

        if (corsPolicy.getAllowedRequestHeaders().isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, corsPolicy.joinAllowedRequestHeaders());
    }

    /**
     * Sets origin header according to the given CORS configuration and HTTP request.
     *
     * @param request the HTTP request
     * @param headers the HTTP headers to modify
     * @return {@code policy} if CORS configuration matches, otherwise {@code null}
     */
    @Nullable
    public static CorsPolicy setCorsOrigin(ServiceRequestContext ctx, HttpRequest request,
                                           ResponseHeadersBuilder headers, CorsConfig config, Logger logger) {

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
