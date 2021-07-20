/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.internal.server.docs;

import java.util.List;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.docs.DocServicePlugin;
import com.linecorp.armeria.server.docs.EndpointInfo;
import com.linecorp.armeria.server.docs.EndpointInfoBuilder;

/**
 * Base {@link DocServicePlugin} implementation that supports the {@link HttpService}.
 */
public abstract class AbstractDocServicePlugin implements DocServicePlugin {

    @VisibleForTesting
    public static EndpointInfo endpointInfo(Route route, String hostnamePattern) {
        final EndpointInfoBuilder builder;
        final RoutePathType pathType = route.pathType();
        final List<String> paths = route.paths();
        switch (pathType) {
            case EXACT:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.EXACT + paths.get(0));
                break;
            case PREFIX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.PREFIX + paths.get(0));
                break;
            case PARAMETERIZED:
                builder = EndpointInfo.builder(hostnamePattern, normalizeParameterized(route));
                break;
            case REGEX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.REGEX + paths.get(0));
                break;
            case REGEX_WITH_PREFIX:
                builder = EndpointInfo.builder(hostnamePattern, RouteUtil.REGEX + paths.get(0));
                builder.regexPathPrefix(RouteUtil.PREFIX + paths.get(1));
                break;
            default:
                // Should never reach here.
                throw new Error();
        }

        builder.availableMimeTypes(availableMimeTypes(route));
        return builder.build();
    }

    private static String normalizeParameterized(Route route) {
        final String path = route.paths().get(0);
        int beginIndex = 0;

        final StringBuilder sb = new StringBuilder();
        for (String paramName : route.paramNames()) {
            final int colonIndex = path.indexOf(':', beginIndex);
            assert colonIndex != -1;
            sb.append(path, beginIndex, colonIndex);
            sb.append('{');
            sb.append(paramName);
            sb.append('}');
            beginIndex = colonIndex + 1;
        }
        if (beginIndex < path.length()) {
            sb.append(path, beginIndex, path.length());
        }
        return sb.toString();
    }

    private static Set<MediaType> availableMimeTypes(Route route) {
        final ImmutableSet.Builder<MediaType> builder = ImmutableSet.builder();
        final Set<MediaType> consumeTypes = route.consumes();
        builder.addAll(consumeTypes);
        if (!consumeTypes.contains(MediaType.JSON_UTF_8)) {
            builder.add(MediaType.JSON_UTF_8);
        }
        return builder.build();
    }
}
