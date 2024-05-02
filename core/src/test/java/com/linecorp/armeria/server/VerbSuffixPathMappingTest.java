/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

class VerbSuffixPathMappingTest {

    @Test
    void withExactPathMapping() {
        final ExactPathMapping exactPathMapping = new ExactPathMapping("/find/me");
        final VerbSuffixPathMapping verbSuffixPathMapping =
                new VerbSuffixPathMapping(exactPathMapping, "update");
        final RoutingResult routingResult =
                verbSuffixPathMapping.apply(RoutingContextTest.create("/find/me:update", "foo=bar")).build();

        assertThat(verbSuffixPathMapping.patternString()).isEqualTo("/find/me:update");

        assertThat(routingResult.isPresent()).isTrue();
        assertThat(routingResult.path()).isEqualTo("/find/me");
        assertThat(routingResult.query()).isEqualTo("foo=bar");
        assertThat(routingResult.pathParams()).isEmpty();
    }

    @Test
    void withPrefixWithExactPathMapping() {
        final ExactPathMapping exactPathMapping = new ExactPathMapping("/find/me");
        final VerbSuffixPathMapping verbSuffixPathMappingWithPrefix =
                (VerbSuffixPathMapping) new VerbSuffixPathMapping(exactPathMapping, "update")
                        .withPrefix("/prefix");
        final RoutingResult routingResultWithPrefix =
                verbSuffixPathMappingWithPrefix.apply(
                        RoutingContextTest.create("/prefix/find/me:update", "foo=bar")).build();

        assertThat(verbSuffixPathMappingWithPrefix.patternString()).isEqualTo("/prefix/find/me:update");

        assertThat(routingResultWithPrefix.isPresent()).isTrue();
        assertThat(routingResultWithPrefix.path()).isEqualTo("/find/me");
        assertThat(routingResultWithPrefix.query()).isEqualTo("foo=bar");
        assertThat(routingResultWithPrefix.pathParams()).isEmpty();
    }

    @Test
    void withParameterizedPathMapping() {
        final ParameterizedPathMapping parameterizedPathMapping = new ParameterizedPathMapping(
                "/users/{user_id}");
        final VerbSuffixPathMapping verbSuffixPathMapping =
                new VerbSuffixPathMapping(parameterizedPathMapping, "update");
        final RoutingResult routingResult =
                verbSuffixPathMapping.apply(RoutingContextTest.create("/users/tom:update", "foo=bar")).build();

        assertThat(verbSuffixPathMapping.patternString()).isEqualTo("/users/:user_id:update");

        assertThat(routingResult.isPresent()).isTrue();
        assertThat(routingResult.path()).isEqualTo("/users/tom");
        assertThat(routingResult.query()).isEqualTo("foo=bar");

        final Map<String, String> pathParams = routingResult.pathParams();
        assertThat(pathParams).containsOnlyKeys("user_id");
        assertThat(pathParams.get("user_id")).isEqualTo("tom");
    }

    @Test
    void withPrefixWithParameterizedPathMapping() {
        final ParameterizedPathMapping parameterizedPathMapping = new ParameterizedPathMapping(
                "/users/{user_id}");
        final VerbSuffixPathMapping verbSuffixPathMappingWithPrefix =
                (VerbSuffixPathMapping) new VerbSuffixPathMapping(parameterizedPathMapping, "update")
                        .withPrefix("/prefix");
        final RoutingResult routingResultWithPrefix =
                verbSuffixPathMappingWithPrefix.apply(
                        RoutingContextTest.create("/prefix/users/tom:update", "foo=bar")).build();

        assertThat(verbSuffixPathMappingWithPrefix.patternString()).isEqualTo("/prefix/users/:user_id:update");

        assertThat(routingResultWithPrefix.isPresent()).isTrue();
        assertThat(routingResultWithPrefix.path()).isEqualTo("/users/tom");
        assertThat(routingResultWithPrefix.query()).isEqualTo("foo=bar");
        final Map<String, String> pathParams2 = routingResultWithPrefix.pathParams();
        assertThat(pathParams2).containsOnlyKeys("user_id");
        assertThat(pathParams2.get("user_id")).isEqualTo("tom");
    }

    @Test
    void returnNull() {
        final ParameterizedPathMapping parameterizedPathMapping = new ParameterizedPathMapping(
                "/users/{user_id}");
        final VerbSuffixPathMapping verbSuffixPathMapping =
                new VerbSuffixPathMapping(parameterizedPathMapping, "update");
        final RoutingResultBuilder routingResultBuilder =
                verbSuffixPathMapping.apply(RoutingContextTest.create("/users/tom:update2", "foo=bar"));

        assertThat(routingResultBuilder).isNull();
    }

    @ParameterizedTest
    @MethodSource("generateFindVerbData")
    void findVerb(String path, @Nullable String expectedVerbWithColon) {
        final String verb = VerbSuffixPathMapping.findVerb(path, true);
        assertThat(verb).isEqualTo(expectedVerbWithColon);
    }

    static Stream<Arguments> generateFindVerbData() {
        return Stream.of(
                Arguments.of("/users/1", null),
                Arguments.of("/users/1/books/1:update", ":update"),
                Arguments.of("/users/1:2/books/1", null),
                Arguments.of("/users/:userId/books/:bookId:update", ":update"),
                Arguments.of("/users/1:/books/1:update", ":update"),
                Arguments.of("/users/:userId:u%p-d.a_t~e", ":u%p-d.a_t~e"),
                Arguments.of("/users/:userId:%E3%83%86%E3%82%B9%E3%83%88", ":%E3%83%86%E3%82%B9%E3%83%88")
        );
    }
}
