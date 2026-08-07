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
 */

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.xds.internal.XdsCommonUtil;
import com.linecorp.armeria.xds.internal.XdsHeaderMatcher;
import com.linecorp.armeria.xds.internal.XdsStringMatcher;

import io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher;
import io.envoyproxy.envoy.config.route.v3.QueryParameterMatcher.QueryParameterMatchSpecifierCase;
import io.envoyproxy.envoy.config.route.v3.RouteMatch;
import io.envoyproxy.envoy.config.route.v3.RouteMatch.PathSpecifierCase;
import io.envoyproxy.envoy.type.matcher.v3.StringMatcher;

final class RouteEntryMatcher {

    private final List<XdsHeaderMatcher> headerMatchers;
    private final List<QueryParamsMatcherImpl> queryParamsMatchers;
    private final PathMatcherImpl pathMatcher;
    private final RouteMatch routeMatch;

    RouteEntryMatcher(RouteMatch routeMatch) {
        this.routeMatch = routeMatch;
        headerMatchers = XdsHeaderMatcher.of(routeMatch.getHeadersList());
        queryParamsMatchers =
                QueryParamsMatcherImpl.fromQueryParamsMatchers(routeMatch.getQueryParametersList());
        pathMatcher = new PathMatcherImpl(routeMatch);
    }

    boolean matches(RequestContext ctx) {
        final HttpRequest req = ctx.request();
        if (routeMatch.hasGrpc()) {
            if (!XdsCommonUtil.isGrpcRequest(req)) {
                return false;
            }
        }

        if (!XdsHeaderMatcher.matchAll(headerMatchers,
                                       req != null ? req.headers() : HttpHeaders.of())) {
            return false;
        }

        if (!queryParamsMatchers.isEmpty()) {
            final QueryParams queryParams = QueryParamsMatcherImpl.fromContext(ctx);
            for (QueryParamsMatcherImpl queryParamsMatcher : queryParamsMatchers) {
                if (!queryParamsMatcher.matches(queryParams)) {
                    return false;
                }
            }
        }

        return pathMatcher.match(ctx);
    }

    @VisibleForTesting
    static class QueryParamsMatcherImpl {

        static List<QueryParamsMatcherImpl> fromQueryParamsMatchers(List<QueryParameterMatcher> paramMatchers) {
            final ImmutableList.Builder<QueryParamsMatcherImpl> builder = ImmutableList.builder();
            for (QueryParameterMatcher paramMatcher : paramMatchers) {
                builder.add(new QueryParamsMatcherImpl(paramMatcher));
            }
            return builder.build();
        }

        private final Predicate<QueryParams> predicate;

        QueryParamsMatcherImpl(QueryParameterMatcher matcher) {
            final QueryParameterMatchSpecifierCase matchCase =
                    matcher.getQueryParameterMatchSpecifierCase();
            switch (matchCase) {
                case PRESENT_MATCH:
                    predicate = params -> params.contains(matcher.getName()) == matcher.getPresentMatch();
                    break;
                case STRING_MATCH:
                    final XdsStringMatcher stringMatcher = new XdsStringMatcher(matcher.getStringMatch());
                    predicate = params -> {
                        final String value = params.get(matcher.getName());
                        if (value == null) {
                            return false;
                        }
                        return stringMatcher.match(value);
                    };
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported query parameter matcher: " + matcher);
            }
        }

        static QueryParams fromContext(RequestContext ctx) {
            final String query = ctx.query();
            final QueryParams queryParams;
            if (query == null) {
                queryParams = QueryParams.of();
            } else {
                queryParams = QueryParams.fromQueryString(query);
            }
            return queryParams;
        }

        boolean matches(QueryParams queryParams) {
            return predicate.test(queryParams);
        }
    }

    @VisibleForTesting
    static class PathMatcherImpl {

        private final Predicate<RequestContext> predicate;

        PathMatcherImpl(RouteMatch routeMatch) {
            final PathSpecifierCase pathSpecifierCase = routeMatch.getPathSpecifierCase();
            final boolean caseSensitive = routeMatch.getCaseSensitive().getValue();
            switch (pathSpecifierCase) {
                case PREFIX:
                    final StringMatcher prefixMatcher = StringMatcher.newBuilder()
                                                                     .setPrefix(routeMatch.getPrefix())
                                                                     .setIgnoreCase(!caseSensitive)
                                                                     .build();
                    final XdsStringMatcher prefixMatcherImpl = new XdsStringMatcher(prefixMatcher);
                    predicate = ctx -> prefixMatcherImpl.match(ctx.path());
                    break;
                case PATH:
                    final StringMatcher pathMatcher = StringMatcher.newBuilder()
                                                                   .setExact(routeMatch.getPath())
                                                                   .setIgnoreCase(!caseSensitive)
                                                                   .build();
                    final XdsStringMatcher pathMatcherImpl = new XdsStringMatcher(pathMatcher);
                    predicate = ctx -> pathMatcherImpl.match(ctx.path());
                    break;
                case SAFE_REGEX:
                    final StringMatcher regexMatcher = StringMatcher.newBuilder()
                                                                    .setSafeRegex(routeMatch.getSafeRegex())
                                                                    .build();
                    final XdsStringMatcher regexMatcherImpl = new XdsStringMatcher(regexMatcher);
                    predicate = ctx -> regexMatcherImpl.match(ctx.path());
                    break;
                case CONNECT_MATCHER:
                    predicate = ctx -> ctx.method() == HttpMethod.CONNECT;
                    break;
                case PATH_SEPARATED_PREFIX:
                    final StringMatcher separatedPrefixMatcher =
                            StringMatcher.newBuilder()
                                         .setPrefix(routeMatch.getPathSeparatedPrefix())
                                         .setIgnoreCase(!caseSensitive)
                                         .build();
                    final XdsStringMatcher separatedPrefixMatcherImpl =
                            new XdsStringMatcher(separatedPrefixMatcher);
                    predicate = ctx -> {
                        final String path = ctx.path();
                        final String pathSeparatedPrefix = routeMatch.getPathSeparatedPrefix();
                        final int pathLen = path.length();
                        final int prefixLen = pathSeparatedPrefix.length();
                        if (pathLen < prefixLen) {
                            return false;
                        }
                        if (!separatedPrefixMatcherImpl.match(path)) {
                            return false;
                        }
                        if (pathLen == prefixLen) {
                            return true;
                        }
                        return path.charAt(prefixLen) == '/';
                    };
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported pathSpecifierCase: " + pathSpecifierCase);
            }
        }

        boolean match(RequestContext ctx) {
            return predicate.test(ctx);
        }
    }
}
