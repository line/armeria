/*
 * Copyright 2017 LINE Corporation
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

import org.junit.jupiter.api.Test;

class PathWithPrefixTest {

    @Test
    void prefix() {
        Route route = Route.builder().path("/foo/", "glob:/bar/**").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/foo/bar/(.*)$", "/foo/bar/**");

        route = Route.builder().path("/foo/", "glob:bar").build();
        assertThat(route.pathType()).isSameAs(RoutePathType.REGEX);
        assertThat(route.paths()).containsExactly("^/foo/(?:.+/)?bar$", "/foo/**/bar");
    }

    @Test
    void patternString() {
        Route route = Route.builder().path("/foo/", "glob:/bar/**").build();
        assertThat(route.patternString()).isEqualTo("/foo/bar/**");

        route = Route.builder().path("/foo/", "glob:bar").build();
        assertThat(route.patternString()).isEqualTo("/foo/**/bar");

        route = Route.builder().path("/foo/", "regex:/(foo|bar)").build();
        assertThat(route.patternString()).isEqualTo("/foo/(foo|bar)");
    }
}
