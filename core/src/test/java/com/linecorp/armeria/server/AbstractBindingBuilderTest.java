/*
 * Copyright 2019 LINE Corporation
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.MediaType;

class AbstractBindingBuilderTest {

    @Test
    void multiRouteBuilder() {
        final List<Route> routes = new AbstractBindingBuilder(ImmutableSet.of("/")) {}
                .path("/foo")
                .pathPrefix("/bar")
                .get("/baz")
                .post("/qux")
                .methods(HttpMethod.GET, HttpMethod.POST)
                .consumes(MediaType.JSON_UTF_8, MediaType.HTML_UTF_8)
                .produces(MediaType.JAVASCRIPT_UTF_8)
                .buildRouteList();

        assertThat(routes.size()).isEqualTo(4);
        assertThat(routes).contains(
                Route.builder()
                     .path("/foo")
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .consumes(MediaType.JSON_UTF_8, MediaType.HTML_UTF_8)
                     .produces(MediaType.JAVASCRIPT_UTF_8)
                     .build(),
                Route.builder()
                     .pathPrefix("/bar")
                     .methods(HttpMethod.GET, HttpMethod.POST)
                     .consumes(MediaType.JSON_UTF_8, MediaType.HTML_UTF_8)
                     .produces(MediaType.JAVASCRIPT_UTF_8)
                     .build(),
                Route.builder()
                     .path("/baz")
                     .methods(HttpMethod.GET)
                     .consumes(MediaType.JSON_UTF_8, MediaType.HTML_UTF_8)
                     .produces(MediaType.JAVASCRIPT_UTF_8)
                     .build(),
                Route.builder()
                     .path("/qux")
                     .methods(HttpMethod.POST)
                     .consumes(MediaType.JSON_UTF_8, MediaType.HTML_UTF_8)
                     .produces(MediaType.JAVASCRIPT_UTF_8)
                     .build()
        );
    }

    @Test
    void shouldSetPath() {
        assertThatThrownBy(() -> new AbstractBindingBuilder(ImmutableSet.of("/")) {}.buildRouteList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least");
    }

    @Test
    void shouldHaveKnownMethods() {
        final List<Route> routes = new AbstractBindingBuilder(ImmutableSet.of("/")) {}.path("/foo")
                                                                                      .buildRouteList();
        assertThat(routes.size()).isEqualTo(1);
        assertThat(routes.get(0).methods()).isEqualTo(HttpMethod.knownMethods());
    }

    @Test
    void shouldFailOnMethodWithoutPath() {
        // empty route path
        assertThatThrownBy(() -> new AbstractBindingBuilder(ImmutableSet.of("/")) {}.methods(HttpMethod.GET)
                                                                                    .buildRouteList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set at least");

        // only method without calling path
        assertThatThrownBy(() -> new AbstractBindingBuilder(ImmutableSet.of("/")) {}.get("/foo")
                                                                                    .methods(HttpMethod.POST)
                                                                                    .buildRouteList())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Should set a path");
    }

    @Test
    void shouldFailOnDuplicatedMethod() {
        assertThatThrownBy(() -> new AbstractBindingBuilder(ImmutableSet.of("/")) {}.get("/foo")
                                                                                    .get("/foo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate HTTP method");
    }

    @Test
    void nonEmptyMethod() {
        assertThatThrownBy(
                () -> new AbstractBindingBuilder(ImmutableSet.of("/")) {}.methods(ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("methods can't be empty.");
    }
}
