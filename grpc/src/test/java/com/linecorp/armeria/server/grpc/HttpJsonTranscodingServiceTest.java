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
package com.linecorp.armeria.server.grpc;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.api.HttpRule;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.internal.server.RouteUtil;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutePathType;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingService.PathVariable;

public class HttpJsonTranscodingServiceTest {

    @ParameterizedTest
    @ArgumentsSource(PathArgumentsProvider.class)
    void shouldAcceptArmeriaPathPatterns(String path,
                                         String patternStringAnswer,
                                         Set<String> pathVariableNamesAnswer,
                                         boolean hasVerb) {
        final HttpRule httpRule = HttpRule.newBuilder().setGet(path).build();
        final HttpJsonTranscodingRouteAndPathVariables routeAndPathVars =
                HttpJsonTranscodingRouteAndPathVariables.of(httpRule);
        final Route route = routeAndPathVars.route();

        assertThat(route.patternString()).isEqualTo(patternStringAnswer);
        assertThat(route.methods()).containsExactly(HttpMethod.GET);

        if (path.startsWith(RouteUtil.EXACT)) {
            assertThat(route.pathType()).isEqualTo(RoutePathType.EXACT);
        } else if (path.startsWith(RouteUtil.PREFIX)) {
            assertThat(route.pathType()).isEqualTo(RoutePathType.PREFIX);
        } else if (path.startsWith(RouteUtil.GLOB) || path.startsWith(RouteUtil.REGEX)) {
            assertThat(route.pathType()).isEqualTo(RoutePathType.REGEX);
        }

        assertThat(route.paramNames().size()).isEqualTo(pathVariableNamesAnswer.size());
        assertThat(route.paramNames()).containsAll(pathVariableNamesAnswer);

        assertThat(routeAndPathVars.pathVariables().size()).isEqualTo(pathVariableNamesAnswer.size());
        assertThat(routeAndPathVars.pathVariables().stream().map(PathVariable::name).collect(toImmutableSet()))
                .containsAll(pathVariableNamesAnswer);

        assertThat(routeAndPathVars.hasVerb()).isEqualTo(hasVerb);
    }

    private static class PathArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    arguments("exact:/v1/messages",
                              "/v1/messages",
                              ImmutableSet.of(), false),
                    arguments("exact:/v1/messages:verb",
                              "/v1/messages:verb",
                              ImmutableSet.of(), true),
                    arguments("exact:/v1/messages:verb/1",
                              "/v1/messages:verb/1",
                              ImmutableSet.of(), false),
                    arguments("exact:/v1/messages/:verb",
                              "/v1/messages/:verb",
                              ImmutableSet.of(), false),
                    arguments("prefix:/v1/messages",
                              "/v1/messages/*",
                              ImmutableSet.of(), false),
                    arguments("regex:^/v1/messages/(?<name>.*)$",
                              "^/v1/messages/(?<name>.*)$",
                              ImmutableSet.of("name"), false),
                    arguments("regex:^/v1/messages/(?<name>.*):verb$",
                              "^/v1/messages/(?<name>.*):verb$",
                              ImmutableSet.of("name"), true),
                    arguments("glob:/v1/messages/*/**",
                              "/v1/messages/*/**",
                              ImmutableSet.of("0", "1"), false),
                    arguments("glob:/v1/messages/*/**:verb",
                              "/v1/messages/*/**:verb",
                              ImmutableSet.of("0", "1"), true)
            );
        }
    }
}
