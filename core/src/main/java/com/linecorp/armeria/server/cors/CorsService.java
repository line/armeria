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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Ascii;

import com.linecorp.armeria.common.DefaultHttpResponse;
import com.linecorp.armeria.common.FilteredHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.HttpStatusClass;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingService;

import io.netty.util.AsciiString;

/**
 * Decorates an HTTP {@link Service} to add the
 * <a href="https://en.wikipedia.org/wiki/Cross-origin_resource_sharing">Cross-Origin Resource Sharing
 * (CORS)</a> support.
 *
 * @see CorsServiceBuilder
 */
public final class CorsService extends SimpleDecoratingService<HttpRequest, HttpResponse> {

    private static final Logger logger = LoggerFactory.getLogger(CorsService.class);

    private static final String ANY_ORIGIN = "*";
    private static final String NULL_ORIGIN = "null";

    private final CorsConfig config;

    /**
     * Creates a new {@link CorsService} that decorates the specified {@code delegate} to add CORS support.
     */
    public CorsService(Service<HttpRequest, HttpResponse> delegate, CorsConfig config) {
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
        if (config.isEnabled()) {
            if (isCorsPreflightRequest(req)) {
                return handleCorsPreflight(req);
            }
            if (config.isShortCircuit() && !validateCorsOrigin(req)) {
                return forbidden();
            }
        }

        return new FilteredHttpResponse(delegate().serve(ctx, req)) {
            @Override
            protected HttpObject filter(HttpObject obj) {
                if (!(obj instanceof HttpHeaders)) {
                    return obj;
                }

                final HttpHeaders headers = (HttpHeaders) obj;
                final HttpStatus status = headers.status();
                if (status == null || status.codeClass() == HttpStatusClass.INFORMATIONAL) {
                    return headers;
                }

                setCorsResponseHeaders(req, headers);
                return headers;
            }
        };
    }

    /**
     * Check for a CORS preflight request.
     *
     * @param request the HTTP request with CORS info
     *
     * @return {@code true} if HTTP request is a CORS preflight request
     */
    private static boolean isCorsPreflightRequest(final HttpRequest request) {
        return request.method() == HttpMethod.OPTIONS &&
               request.headers().contains(HttpHeaderNames.ORIGIN) &&
               request.headers().contains(HttpHeaderNames.ACCESS_CONTROL_REQUEST_METHOD);
    }

    /**
     * Handles CORS preflight by setting the appropriate headers.
     *
     * @param req the decoded HTTP request
     */
    private HttpResponse handleCorsPreflight(HttpRequest req) {
        DefaultHttpResponse res = new DefaultHttpResponse();
        HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);
        if (setCorsOrigin(req, headers)) {
            setCorsAllowMethods(headers);
            setCorsAllowHeaders(headers);
            setCorsAllowCredentials(headers);
            setCorsMaxAge(headers);
            setPreflightHeaders(headers);
        }

        res.write(headers);
        res.close();
        return res;
    }

    /**
     * This is a non CORS specification feature which enables the setting of preflight
     * response headers that might be required by intermediaries.
     *
     * @param headers the {@link HttpHeaders} to which the preflight headers should be added.
     */
    private void setPreflightHeaders(final HttpHeaders headers) {
        for (Map.Entry<AsciiString, String> entry : config.preflightResponseHeaders()) {
            headers.add(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Emit CORS headers if origin was found.
     *
     * @param req the HTTP request with the CORS info
     * @param headers the headers to modify
     */
    private void setCorsResponseHeaders(final HttpRequest req, HttpHeaders headers) {
        if (setCorsOrigin(req, headers)) {
            setCorsAllowCredentials(headers);
            setCorsAllowHeaders(headers);
            setCorsExposeHeaders(headers);
        }
    }

    /**
     * Return a "forbidden" response.
     */
    private static HttpResponse forbidden() {
        return HttpResponse.of(HttpStatus.FORBIDDEN);
    }

    /**
     * Validates if origin matches the CORS requirements.
     *
     * @param request the HTTP request to check
     * @return {@code true} if origin matches
     */
    private boolean validateCorsOrigin(final HttpRequest request) {
        if (config.isAnyOriginSupported()) {
            return true;
        }

        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        return origin == null ||
               NULL_ORIGIN.equals(origin) && config.isNullOriginAllowed() ||
               config.origins().contains(Ascii.toLowerCase(origin));
    }

    /**
     * Sets origin header according to the given CORS configuration and HTTP request.
     *
     * @param request the HTTP request
     * @param headers the HTTP headers to modify
     *
     * @return {@code true} if CORS configuration matches, otherwise false
     */
    private boolean setCorsOrigin(final HttpRequest request, final HttpHeaders headers) {
        if (!config.isEnabled()) {
            return false;
        }

        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            if (NULL_ORIGIN.equals(origin) && config.isNullOriginAllowed()) {
                setCorsNullOrigin(headers);
                return true;
            }
            if (config.isAnyOriginSupported()) {
                if (config.isCredentialsAllowed()) {
                    echoCorsRequestOrigin(request, headers);
                    setCorsVaryHeader(headers);
                } else {
                    setCorsAnyOrigin(headers);
                }
                return true;
            }
            if (config.origins().contains(Ascii.toLowerCase(origin))) {
                setCorsOrigin(headers, origin);
                setCorsVaryHeader(headers);
                return true;
            }
            logger.debug("Request origin [{}]] was not among the configured origins [{}]",
                         origin, config.origins());
        }
        return false;
    }

    private static void setCorsOrigin(final HttpHeaders headers, final String origin) {
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }

    private static void echoCorsRequestOrigin(final HttpRequest request, final HttpHeaders headers) {
        setCorsOrigin(headers, request.headers().get(HttpHeaderNames.ORIGIN));
    }

    private static void setCorsVaryHeader(final HttpHeaders headers) {
        headers.set(HttpHeaderNames.VARY, HttpHeaderNames.ORIGIN.toString());
    }

    private static void setCorsAnyOrigin(final HttpHeaders headers) {
        setCorsOrigin(headers, ANY_ORIGIN);
    }

    private static void setCorsNullOrigin(final HttpHeaders headers) {
        setCorsOrigin(headers, NULL_ORIGIN);
    }

    private void setCorsAllowCredentials(final HttpHeaders headers) {
        if (config.isCredentialsAllowed() &&
            !headers.get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN).equals(ANY_ORIGIN)) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    private void setCorsExposeHeaders(final HttpHeaders headers) {
        final Set<AsciiString> exposedHeaders = config.exposedHeaders();
        if (exposedHeaders.isEmpty()) {
            return;
        }

        for (AsciiString header : exposedHeaders) {
            headers.add(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, header.toString());
        }
    }

    private void setCorsAllowMethods(final HttpHeaders headers) {
        List<String> methods = config.allowedRequestMethods()
                                     .stream().map(HttpMethod::name).collect(Collectors.toList());
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, methods);
    }

    private void setCorsAllowHeaders(final HttpHeaders headers) {
        final Set<AsciiString> allowedHeaders = config.allowedRequestHeaders();
        if (allowedHeaders.isEmpty()) {
            return;
        }

        for (AsciiString header : allowedHeaders) {
            headers.add(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, header.toString());
        }
    }

    private void setCorsMaxAge(final HttpHeaders headers) {
        headers.setLong(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, config.maxAge());
    }
}
