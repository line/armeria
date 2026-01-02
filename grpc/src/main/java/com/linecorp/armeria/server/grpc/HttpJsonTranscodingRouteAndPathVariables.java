/*
 * Copyright 2025 LINE Corporation
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
package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.HttpRule;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RouteBuilder;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment.PathMappingType;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.Stringifier;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.VerbPathSegment;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.PathVariable;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.PathVariable.ValueDefinition.Type;

final class HttpJsonTranscodingRouteAndPathVariables {

    private static final Logger logger =
            LoggerFactory.getLogger(HttpJsonTranscodingRouteAndPathVariables.class);

    @Nullable
    static HttpJsonTranscodingRouteAndPathVariables of(HttpRule httpRule) {
        final RouteBuilder builder = Route.builder();
        final String path;
        switch (httpRule.getPatternCase()) {
            case GET:
                builder.methods(HttpMethod.GET);
                path = httpRule.getGet();
                break;
            case PUT:
                builder.methods(HttpMethod.PUT);
                path = httpRule.getPut();
                break;
            case POST:
                builder.methods(HttpMethod.POST);
                path = httpRule.getPost();
                break;
            case DELETE:
                builder.methods(HttpMethod.DELETE);
                path = httpRule.getDelete();
                break;
            case PATCH:
                builder.methods(HttpMethod.PATCH);
                path = httpRule.getPatch();
                break;
            case CUSTOM:
            default:
                logger.warn("Ignoring unsupported route pattern: pattern={}, httpRule={}",
                            httpRule.getPatternCase(), httpRule);
                return null;
        }

        // Check whether the path is Armeria-native.
        if (path.startsWith(RouteUtil.EXACT) ||
            path.startsWith(RouteUtil.PREFIX) ||
            path.startsWith(RouteUtil.GLOB) ||
            path.startsWith(RouteUtil.REGEX)) {

            final Route route = builder.path(path).build();
            final List<PathVariable> vars =
                    route.paramNames().stream()
                         .map(name -> new PathVariable(
                                 null, name, ImmutableList.of(
                                         new PathVariable.ValueDefinition(Type.REFERENCE, name))))
                         .collect(toImmutableList());
            boolean hasVerb = false;
            if (path.startsWith(RouteUtil.EXACT)) {
                hasVerb = containsVerbPath(path, true);
            } else if (path.startsWith(RouteUtil.GLOB) || path.startsWith(RouteUtil.REGEX)) {
                hasVerb = containsVerbPath(route.paths().get(0), false);
            }

            return new HttpJsonTranscodingRouteAndPathVariables(route, vars, hasVerb);
        }

        final List<PathSegment> segments = HttpJsonTranscodingPathParser.parse(path);

        PathMappingType pathMappingType =
                segments.stream().allMatch(segment -> segment.support(PathMappingType.PARAMETERIZED)) ?
                PathMappingType.PARAMETERIZED : PathMappingType.GLOB;
        final boolean hasVerb;
        if (segments.get(segments.size() - 1) instanceof VerbPathSegment) {
            pathMappingType = PathMappingType.REGEX;
            hasVerb = true;
        } else {
            hasVerb = false;
        }

        if (pathMappingType == PathMappingType.PARAMETERIZED) {
            builder.path(Stringifier.segmentsToPath(PathMappingType.PARAMETERIZED, segments, true));
        } else if (pathMappingType == PathMappingType.GLOB) {
            builder.glob(Stringifier.segmentsToPath(PathMappingType.GLOB, segments, true));
        } else {
            builder.regex('^' + Stringifier.segmentsToPath(PathMappingType.REGEX, segments, true) + '$');
        }
        return new HttpJsonTranscodingRouteAndPathVariables(
                builder.build(), PathVariable.from(segments, pathMappingType), hasVerb);
    }

    private static boolean containsVerbPath(String path, boolean exact) {
        final String verbPathRegex;
        if (exact) {
            verbPathRegex = "(?<!/):[A-Za-z0-9]+$";
        } else {
            verbPathRegex = "(?<!/):[A-Za-z]+\\$(?!/)";
        }

        final Pattern pattern = Pattern.compile(verbPathRegex);
        final Matcher matcher = pattern.matcher(path);
        return matcher.find();
    }

    private final Route route;
    private final List<PathVariable> pathVariables;
    private final boolean hasVerb;

    private HttpJsonTranscodingRouteAndPathVariables(Route route, List<PathVariable> pathVariables,
                                                     boolean hasVerb) {
        this.route = route;
        this.pathVariables = pathVariables;
        this.hasVerb = hasVerb;
    }

    Route route() {
        return route;
    }

    List<PathVariable> pathVariables() {
        return pathVariables;
    }

    boolean hasVerb() {
        return hasVerb;
    }
}
