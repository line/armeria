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

import java.util.Map;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;

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

    private static final String DELIMITER = ",";
    private static final Joiner HEADER_JOINER = Joiner.on(DELIMITER);
    private static final Logger logger = LoggerFactory.getLogger(CorsService.class);

    static final String ANY_ORIGIN = "*";
    static final String NULL_ORIGIN = "null";

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
            if (config.isShortCircuit() &&
                config.getPolicy(req.headers().get(HttpHeaderNames.ORIGIN)) == null) {
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
     * This is a non CORS specification feature which enables the setting of preflight
     * response headers that might be required by intermediaries.
     *
     * @param headers the {@link HttpHeaders} to which the preflight headers should be added.
     */
    void setPreflightHeaders(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        for (Map.Entry<AsciiString, String> entry : policy.preflightResponseHeaders()) {
            headers.add(entry.getKey(), entry.getValue());
        }
    }

    void setCorsAllowCredentials(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        if (policy.isCredentialsAllowed() &&
            !headers.get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN).equals(CorsService.ANY_ORIGIN)) {
            headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        }
    }

    void setCorsExposeHeaders(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        if (policy.exposedHeaders().isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_EXPOSE_HEADERS, HEADER_JOINER.join(policy.exposedHeaders()));
    }

    void setCorsAllowMethods(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        final String methods = policy.allowedRequestMethods()
                                     .stream().map(HttpMethod::name).collect(Collectors.joining(DELIMITER));
        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, methods);
    }

    void setCorsAllowHeaders(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        if (policy.allowedRequestHeaders().isEmpty()) {
            return;
        }

        headers.set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS,
                    HEADER_JOINER.join(policy.allowedRequestHeaders()));
    }

    void setCorsMaxAge(final CorsPolicy policy, final HttpHeaders headers) {
        requireNonNull(policy, "policy");
        headers.setLong(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, policy.maxAge());
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
        final HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);
        final CorsPolicy policy = setCorsOrigin(req, headers);
        if (policy != null) {
            setCorsAllowMethods(policy, headers);
            setCorsAllowHeaders(policy, headers);
            setCorsAllowCredentials(policy, headers);
            setCorsMaxAge(policy, headers);
            setPreflightHeaders(policy, headers);
        }

        return HttpResponse.of(headers);
    }

    /**
     * Emit CORS headers if origin was found.
     *
     * @param req the HTTP request with the CORS info
     * @param headers the headers to modify
     */
    private void setCorsResponseHeaders(final HttpRequest req, HttpHeaders headers) {
        final CorsPolicy policy = setCorsOrigin(req, headers);
        if (policy != null) {
            setCorsAllowCredentials(policy, headers);
            setCorsAllowHeaders(policy, headers);
            setCorsExposeHeaders(policy, headers);
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
     * @return {@code policy} if CORS configuration matches, otherwise null
     */
    @Nullable
    private CorsPolicy setCorsOrigin(final HttpRequest request, final HttpHeaders headers) {
        if (!config.isEnabled()) {
            return null;
        }

        final String origin = request.headers().get(HttpHeaderNames.ORIGIN);
        if (origin != null) {
            final CorsPolicy policy = config.getPolicy(origin);
            if (policy == null) {
                logger.debug("There is no CORS policy configured for the request origin [{}]]", origin);
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
}
