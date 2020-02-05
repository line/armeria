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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

/**
 * An {@link HttpService} that sends a redirect response such as {@code "307 Temporary Redirect"}.
 * You have to specify a template or a {@link Function} that generates the value of the {@code "Location"}
 * header.
 *
 * <h3>Using a location template</h3>
 *
 * <p>You can choose one of the following template styles where the path parameters are substituted with
 * the values retrieved from {@link ServiceRequestContext#pathParam(String)}:</p>
 *
 * <ul>
 *   <li>{@code /new} (no path parameters)
 *     <pre>{@code
 *     ServerBuilder sb = Server.builder();
 *     // e.g. /old -> /new
 *     sb.service("/old", new RedirectService("/new");
 *     }</pre>
 *   </li>
 *   <li>{@code /new/{var}} (curly-brace style parameters)
 *     <pre>{@code
 *     // e.g. /old/foo -> /new/foo
 *     sb.service("/old/{var}", new RedirectService("/new/{var}");
 *     }</pre>
 *   </li>
 *   <li>{@code /new/:var1/:var2} (colon style parameters)
 *     <pre>{@code
 *     // e.g. /old/foo/bar -> /new/foo/bar
 *     sb.service("/old/:var1/:var2", new RedirectService("/new/:var1/:var2"));
 *     }</pre>
 *   </li>
 *   <li>{@code http://host/new} (full URL without path parameters)
 *     <pre>{@code
 *     // e.g. /old -> http://host/new
 *     sb.service("/old", new RedirectService("http://host/new"));
 *     }</pre>
 *   </li>
 *   <li>{@code http://host/new/{var}} (full URL with curly-brace style parameters)
 *     <pre>{@code
 *     // e.g. /old/foo -> http://host/new/foo
 *     sb.service("/old/{var}", new RedirectService("http://host/new/{var}"));
 *     }</pre>
 *   </li>
 *   <li>{@code http://host/new/:var1/:var2} (full URL with colon style parameters)
 *     <pre>{@code
 *     // e.g. /old/foo/bar -> http://host/new/foo/bar
 *     sb.service("/old/:var1/:var2", new RedirectService("http://host/new/:var1/:var2"));
 *     }</pre>
 *   </li>
 * </ul>
 *
 * <h3>Using a location function</h3>
 *
 * <p>You can also specify a custom function to generate a location which cannot be generated with a location
 * template:</p>
 *
 * <pre>{@code
 * ServerBuilder sb = Server.builder();
 * // e.g. /foo/bar -> /NNNNNN/foo_bar
 * sb.service("/:var1/:var2", new RedirectService(ctx -> {
 *     String name = ctx.pathParam("var1") + "_" + ctx.pathParam("var2");
 *     return String.format("/%d/%s", name.hashCode(), name);
 * });
 * }</pre>
 *
 * <h3>Specifying an alternative status code</h3>
 *
 * <p>By default, {@link RedirectService} responds with {@link HttpStatus#TEMPORARY_REDIRECT 307 Temporary
 * Redirect} status. You can specify alternative status such as {@link HttpStatus#MOVED_PERMANENTLY 301 Moved
 * Permanently} when calling the constructor.</p>
 *
 * <h3>Preserving a query string (or not)</h3>
 *
 * <p>By default, {@link RedirectService} preserves the query string in the request URI when generating
 * a new location. For example, if you bound {@code new RedirectService("/new")} at {@code "/old"},
 * a request to {@code "/old?foo=bar"} will be redirected to {@code "/new?foo=bar"}. You can disable
 * this behavior by specifying {@code false} for the {@code preserveQueryString} parameter
 * when constructing the service.</p>
 *
 * <p>Note that {@link RedirectService} will not append the query string if the generated location already
 * contains a query string, regardless of the {@code preserveQueryString} parameter value. For example,
 * the following location function never preserves the original query string:</p>
 *
 * <pre>{@code
 * ServiceBuilder sb = Server.builder();
 * // /old?foo=bar -> /new?redirected=1 (?foo=bar is ignored.)
 * sb.service("/old", new RedirectService("/new?redirected=1"));
 * }</pre>
 */
public final class RedirectService extends AbstractHttpService {

    private static final Pattern VALID_DEFAULT_URI_PATTERN = Pattern
            .compile("(?:(?:^https?:/{2}(?:([^:]+:)?[^:@]+@)?[^:]+)(?::[0-9]{1,5})?)?" +
                    "(?:/[^/{}:]+|/:[^/{}]+|/\\{[^/{}]+})+/?");
    private static final Pattern PATTERN_PARAMS_START = Pattern.compile("/:|/\\{");

    private final HttpStatus httpStatus;
    private final Function<? super ServiceRequestContext, String> locationFunction;
    private final boolean preserveQueryString;

    @Nullable
    private Set<String> paramNames;

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}, preserving the query string in the request URI.
     *
     * @param locationPattern the location pattern that is used to generate a redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(String locationPattern) {
        this(locationPattern, true);
    }

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}.
     *
     * @param locationPattern the location pattern that is used to generate a redirect location.
     * @param preserveQueryString whether to preserve the query string in the generated redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(String locationPattern, boolean preserveQueryString) {
        this(HttpStatus.TEMPORARY_REDIRECT, locationPattern, preserveQueryString);
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction},
     * preserving the query string in the request URI.
     *
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     */
    public RedirectService(Function<? super ServiceRequestContext, String> locationFunction) {
        this(locationFunction, true);
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction}.
     *
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     * @param preserveQueryString whether to preserve the query string in the generated redirect location.
     */
    public RedirectService(Function<? super ServiceRequestContext, String> locationFunction,
                           boolean preserveQueryString) {
        this(HttpStatus.TEMPORARY_REDIRECT, locationFunction, preserveQueryString);
    }

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}, preserving the query string in the request URI.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link HttpService} will return.
     * @param locationPattern the location pattern that is used to generate a redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(HttpStatus redirectStatus, String locationPattern) {
        this(redirectStatus, locationPattern, true);
    }

    /**
     * Creates a new instance that redirects to the location constructed with the specified
     * {@code locationPattern}.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link HttpService} will return.
     * @param locationPattern the location pattern that is used to generate a redirect location.
     * @param preserveQueryString whether to preserve the query string in the generated redirect location.
     *
     * @throws IllegalArgumentException if the specified {@code locationPattern} is unsupported or invalid
     */
    public RedirectService(HttpStatus redirectStatus, String locationPattern, boolean preserveQueryString) {
        this(redirectStatus, toLocationFunction(locationPattern), preserveQueryString);

        final Matcher m = PATTERN_PARAMS_START.matcher(locationPattern);
        if (m.find()) {
            paramNames = Route.builder().path(locationPattern.substring(m.start())).build().paramNames();
        }
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction},
     * preserving the query string in the request URI.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link HttpService} will return.
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     */
    public RedirectService(HttpStatus redirectStatus,
                           Function<? super ServiceRequestContext, String> locationFunction) {
        this(redirectStatus, locationFunction, true);
    }

    /**
     * Creates a new instance that redirects to the location returned by {@code locationFunction}.
     *
     * @param redirectStatus the {@link HttpStatus} that the {@link HttpService} will return.
     * @param locationFunction a {@link Function} that takes a {@link ServiceRequestContext}
     *                         and returns a new location.
     * @param preserveQueryString whether to preserve the query string in the generated redirect location.
     */
    public RedirectService(HttpStatus redirectStatus,
                           Function<? super ServiceRequestContext, String> locationFunction,
                           boolean preserveQueryString) {
        requireNonNull(redirectStatus, "redirectStatus");
        requireNonNull(locationFunction, "locationFunction");
        if (redirectStatus.compareTo(HttpStatus.MULTIPLE_CHOICES) < 0 ||
            redirectStatus.compareTo(HttpStatus.TEMPORARY_REDIRECT) > 0) {
            throw new IllegalArgumentException("redirectStatus: " + redirectStatus + " (expected: 300 .. 307)");
        }
        httpStatus = redirectStatus;
        this.locationFunction = locationFunction;
        this.preserveQueryString = preserveQueryString;
    }

    /**
     * NB: For now we redirect all methods.
     */
    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        String location = locationFunction.apply(ctx);
        requireNonNull(location, "locationFunction returned null.");

        if (preserveQueryString) {
            location = appendQueryString(ctx, location);
        }

        return HttpResponse.of(ResponseHeaders.of(httpStatus,
                                                  HttpHeaderNames.LOCATION, location));
    }

    private static String appendQueryString(ServiceRequestContext ctx, String location) {
        final String query = ctx.query();
        if (query == null || location.lastIndexOf('?') >= 0) {
            // The request URI does not have a query string or
            // The new location includes a query string already.
            return location;
        }

        return location + '?' + query;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        if (paramNames != null) {
            final Set<String> params = cfg.route().paramNames();

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
            @SuppressWarnings("RegExpRedundantEscape")
            final String tokenPattern = "\\{" + e.getKey() + "\\}|:" + e.getKey();
            pathPattern = pathPattern.replaceAll(tokenPattern, e.getValue());
        }
        return pathPattern;
    }

    private static boolean isDefaultUriPattern(String pathPattern) {
        return VALID_DEFAULT_URI_PATTERN.matcher(pathPattern).matches();
    }
}
