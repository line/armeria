/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;

/**
 * An {@link HttpService} that implements an HTTP redirection.
 *
 * <p>This class will redirect requests from its bound {@link Service} path pattern
 * to a new location pattern. Currently only these patterns are supported:
 * <ul>
 *   <li>{@code /new} (no path parameters)</li>
 *   <li>{@code /new/{var1}} (curly-brace style parameters)</li>
 *   <li>{@code /new/:var1/new/:var2} (colon style parameters)</li>
 *   <li>{@code http://host/new} (full URL without path parameters)</li>
 *   <li>{@code http://host/new/{var1}} (full URL with curly-brace style parameters)</li>
 *   <li>{@code http://host/new/:var1/new/:var2} (full URL with colon style parameters)</li>
 * </ul>
 * The {@link RedirectService} will return {@link HttpStatus#TEMPORARY_REDIRECT 307 Temporary Redirect}
 * by default.
 * <pre>{@code
 * ServerBuilder sb = ...;
 * sb.service("/old/", new RedirectService("/new"));
 * sb.service("/old/{var}", new RedirectService("/new/{var}"));
 * sb.service("/old/{var}", new RedirectService(ctx -> "/new/" + ctx.pathParam("var")));
 *
 * sb.service("/old/{var}", new RedirectService(HttpStatus.MOVED_PERMANENTLY, "/new/{var}"));
 * sb.service("/old/{var}", new RedirectService("http://user:name@localhost:8080/search/{var}"));
 * }</pre>
 */
public class RedirectService extends AbstractHttpService {

    private static final Pattern VALID_DEFAULT_URI_PATTERN = Pattern
            .compile("(?:(?:^https?:/{2}(?:([^:]+:)?[^:@]+@)?[^:]+)(?::[0-9]{1,5})?)?" +
                    "(?:/[^/{}:]+|/:[^/{}]+|/\\{[^/{}]+})+/?");
    private static final Pattern PATTERN_PARAMS_START = Pattern.compile("/:|/\\{");

    private final HttpStatus httpStatus;
    private final Function<? super ServiceRequestContext, String> locationFunction;

    @Nullable
    private Set<String> paramNames;

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}.
     *
     * @param locationPattern the location pattern that is used to generate a redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(String locationPattern) {
        this(HttpStatus.TEMPORARY_REDIRECT, locationPattern);
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction}.
     *
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     */
    public RedirectService(Function<? super ServiceRequestContext, String> locationFunction) {
        this(HttpStatus.TEMPORARY_REDIRECT, locationFunction);
    }

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link Service} will return.
     * @param locationPattern the location pattern that is used to generate a redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(HttpStatus redirectStatus, String locationPattern) {
        this(redirectStatus, toLocationFunction(locationPattern));

        final Matcher m = PATTERN_PARAMS_START.matcher(locationPattern);
        if (m.find()) {
            paramNames = PathMapping.of(locationPattern.substring(m.start())).paramNames();
        }
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction}.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link Service} will return.
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     */
    public RedirectService(HttpStatus redirectStatus,
                           Function<? super ServiceRequestContext, String> locationFunction) {
        requireNonNull(redirectStatus, "redirectStatus");
        requireNonNull(locationFunction, "locationFunction");
        if (redirectStatus.compareTo(HttpStatus.MULTIPLE_CHOICES) < 0 ||
            redirectStatus.compareTo(HttpStatus.TEMPORARY_REDIRECT) > 0) {
            throw new IllegalArgumentException("redirectStatus: " + redirectStatus + " (expected: 300 .. 307)");
        }
        httpStatus = redirectStatus;
        this.locationFunction = locationFunction;
    }

    /**
     * NB: For now we redirect all methods.
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req)
            throws Exception {
        return HttpResponse.of(HttpHeaders.of(httpStatus)
                .set(HttpHeaderNames.LOCATION, locationFunction.apply(ctx)));
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (paramNames != null) {
            final Set<String> params = cfg.pathMapping().paramNames();

            // Find out if old path and new path are compatible
            for (String param : paramNames) {
                if (!params.contains(param)) {
                    throw new IllegalArgumentException("pathParams: " + param + " (no matching param in " +
                                                       params + ')');
                }
            }
            // We don't need the paramNames anymore
            paramNames = null;
        }

        super.serviceAdded(cfg);
    }

    private static Function<? super ServiceRequestContext, String> toLocationFunction(String locationPattern) {
        requireNonNull(locationPattern, "locationPattern");
        if (!isDefaultUriPattern(locationPattern)) {
            throw new IllegalArgumentException("locationPattern: " + locationPattern);
        }
        return ctx -> populatePatternParams(locationPattern, ctx.pathParams());
    }

    private static String populatePatternParams(String pathPattern, Map<String, String> pathParams) {
        for (Entry<String, String> e : pathParams.entrySet()) {
            final String tokenPattern = "\\{" + e.getKey() + "\\}|:" + e
                    .getKey();
            pathPattern = pathPattern.replaceAll(tokenPattern, e.getValue());
        }
        return pathPattern;
    }

    private static boolean isDefaultUriPattern(String pathPattern) {
        return VALID_DEFAULT_URI_PATTERN.matcher(pathPattern).matches();
    }
}
