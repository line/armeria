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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;
import org.junit.jupiter.params.provider.ArgumentsSource;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingPathParser.PathSegment.PathMappingType;

class HttpJsonTranscodingPathParserTest {

    @ParameterizedTest
    @ArgumentsSource(PathArgumentsProvider.class)
    void shouldGenerateArmeriaPathPatterns(String originalPath,
                                           String generatedPathAnswer,
                                           PathMappingType typeAnswer,
                                           Map<String, String> pathParams,
                                           Map<String, String> pathVariablesAnswer) {
        final List<HttpJsonTranscodingPathParser.PathSegment> segments =
                HttpJsonTranscodingPathParser.parse(originalPath);

        final String generatedPath;
        if (typeAnswer == PathMappingType.PARAMETERIZED) {
            generatedPath = HttpJsonTranscodingPathParser.Stringifier.asParameterizedPath(segments, true);
        } else {
            assertThatThrownBy(() -> HttpJsonTranscodingPathParser.Stringifier.asParameterizedPath(segments,
                                                                                                   true))
                    .isInstanceOf(UnsupportedOperationException.class);
            generatedPath = HttpJsonTranscodingPathParser.Stringifier.asGlobPath(segments, true);
        }
        assertThat(generatedPath).isEqualTo(generatedPathAnswer);

        // Check path variables and their values.
        final List<HttpJsonTranscodingService.PathVariable> pathVariables =
                HttpJsonTranscodingService.PathVariable.from(segments, typeAnswer);

        final ServiceRequestContext ctx = mock(ServiceRequestContext.class);
        pathParams.forEach((name, value) -> when(ctx.pathParam(name)).thenReturn(value));

        final Map<String, String> populated =
                HttpJsonTranscodingService.populatePathVariables(ctx, pathVariables);

        assertThat(populated.size()).isEqualTo(pathVariablesAnswer.size());
        pathVariablesAnswer.forEach((key, value) -> assertThat(populated.get(key)).isEqualTo(value));
    }

    private static class PathArgumentsProvider implements ArgumentsProvider {
        @Override
        public Stream<? extends Arguments> provideArguments(ExtensionContext context) {
            return Stream.of(
                    arguments("/v1/messages/{message_id}",
                              "/v1/messages/:message_id",
                              PathMappingType.PARAMETERIZED,
                              ImmutableMap.of("message_id", "1"),
                              ImmutableMap.of("message_id", "1")),
                    arguments("/v1/users/{user_id}/messages/{message_id}",
                              "/v1/users/:user_id/messages/:message_id",
                              PathMappingType.PARAMETERIZED,
                              ImmutableMap.of("user_id", "1", "message_id", "2"),
                              ImmutableMap.of("user_id", "1", "message_id", "2")),
                    arguments("/v1/{name=messages/*}",
                              "/v1/messages/:p0",
                              PathMappingType.PARAMETERIZED,
                              ImmutableMap.of("p0", "1"),
                              ImmutableMap.of("name", "messages/1")),
                    arguments("/v1/{name=messages/{name2=*}}",
                              "/v1/messages/:name2",
                              PathMappingType.PARAMETERIZED,
                              ImmutableMap.of("name2", "1"),
                              ImmutableMap.of("name", "messages/1", "name2", "1")),
                    arguments("/v1/{name=messages/{name2=*/foo/{name3=*}}}",
                              "/v1/messages/:p0/foo/:name3",
                              PathMappingType.PARAMETERIZED,
                              ImmutableMap.of("p0", "1", "name3", "2"),
                              ImmutableMap.of("name", "messages/1/foo/2", "name2", "1/foo/2", "name3", "2")),
                    arguments("/v1/messages/{name=**}",
                              "/v1/messages/**",
                              PathMappingType.GLOB,
                              ImmutableMap.of("0", "1/2/3"),
                              ImmutableMap.of("name", "1/2/3")),
                    arguments("/v1/messages/{name=*}/{name2=**}",
                              "/v1/messages/*/**",
                              PathMappingType.GLOB,
                              ImmutableMap.of("0", "1", "1", "2/3"),
                              ImmutableMap.of("name", "1", "name2", "2/3")),
                    arguments("/v1/messages/{name=*/{name2=**}}",
                              "/v1/messages/*/**",
                              PathMappingType.GLOB,
                              ImmutableMap.of("0", "1", "1", "2/3"),
                              ImmutableMap.of("name", "1/2/3", "name2", "2/3"))
            );
        }
    }

    @Test
    void shouldThrowExceptionIfInvalidPathSpecified() {
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var=**/x}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var=**}/"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var=**}/{var2=*}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{var=}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/***"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse("/v1/{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HttpJsonTranscodingPathParser.parse(null))
                .isInstanceOf(NullPointerException.class);
    }
}
