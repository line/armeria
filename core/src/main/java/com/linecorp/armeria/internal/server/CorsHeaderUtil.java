package com.linecorp.armeria.internal.server;

import com.google.common.base.Strings;
import com.linecorp.armeria.common.*;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.cors.CorsConfig;
import com.linecorp.armeria.server.cors.CorsPolicy;
import com.linecorp.armeria.server.cors.CorsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class CorsHeaderUtil {

    private static final Logger logger = LoggerFactory.getLogger(CorsHeaderUtil.class);

    public static final String ANY_ORIGIN = "*";
    public static final String NULL_ORIGIN = "null";

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

    public static void setCorsAllowHeaders(RequestHeaders requestHeaders, ResponseHeadersBuilder headers, CorsPolicy corsPolicy) {
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
