/*
 * Copyright 2015 LINE Corporation
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

import static com.linecorp.armeria.server.RoutingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GlobPathMappingTest {

    @Test
    void testSingleAsterisk() {
        pass("*", "/foo", "/foo/bar", "/foo/bar/baz");
        fail("*", "/foo/", "/foo/bar/", "/foo/bar/baz/");

        pass("*/", "/foo/", "/foo/bar/", "/foo/bar/baz/");
        fail("*/", "/foo", "/foo/bar", "/foo/bar/baz");

        pass("*.js", "/.js", "/foo.js", "/foo/bar.js", "/foo/bar/baz.js");
        fail("*.js", "/foo.js/", "/foo.js/bar", "/foo.json");

        pass("/foo/*", "/foo/bar", "/foo/baz");
        fail("/foo/*", "/foo", "/foo/", "/foo/bar/", "/foo/bar/baz", "/foo/bar/baz/", "/baz/foo/bar");

        pass("/foo/*/", "/foo/bar/", "/foo/baz/");
        fail("/foo/*/", "/foo/", "/foo//", "/foo/bar", "/foo/bar/baz", "/foo/bar/baz/", "/baz/foo/bar/");

        pass("/*/baz", "/foo/baz", "/bar/baz");
        fail("/*/baz", "/foo/baz/", "/bar/baz/", "//baz", "//baz/");

        pass("/foo/*/bar/*/baz/*", "/foo/1/bar/2/baz/3");
        fail("/foo/*/bar/*/baz/*", "/foo/1/bar/2/baz/3/");
    }

    @Test
    void testDoubleAsterisk() {
        pass("**/baz", "/baz", "/foo/baz", "/foo/bar/baz");
        fail("**/baz", "/baz/", "/baz/bar");

        pass("**/baz/", "/baz/", "/foo/baz/", "/foo/bar/baz/");
        fail("**/baz/", "/baz", "/baz/bar");

        pass("/foo/**", "/foo/", "/foo/bar", "/foo/bar/", "/foo/bar/baz", "/foo/bar/baz");
        fail("/foo/**", "/foo", "/bar/foo/");

        pass("/foo/**/baz", "/foo/baz", "/foo/bar/baz", "/foo/alice/bob/charles/baz");
        fail("/foo/**/baz", "/foobaz");

        pass("foo/**/baz", "/foo/baz", "/alice/foo/bar/baz", "/alice/bob/foo/bar/baz/baz");
    }

    @Test
    void testRelativePattern() {
        pass("baz", "/baz", "/bar/baz", "/foo/bar/baz");
        fail("baz", "/baz/", "/bar/baz/", "/foo/bar/baz/", "/foo/bar/baz/quo");

        pass("bar/baz", "/bar/baz", "/foo/bar/baz");
        fail("bar/baz", "/bar/baz/", "/foo/bar/baz/", "/foo/bar/baz/quo");
    }

    @Test
    void testPathValidation() {
        final Route route = glob("**");
        assertThrows(IllegalArgumentException.class,
                     () -> route.apply(create("not/an/absolute/path")));
    }

    @Test
    void testLoggerName() throws Exception {
        assertThat(glob("/foo/bar/**").loggerName()).isEqualTo("foo.bar.__");
        assertThat(glob("foo").loggerName()).isEqualTo("__.foo");
    }

    @Test
    void testMetricName() throws Exception {
        assertThat(glob("/foo/bar/**").meterTag()).isEqualTo("glob:/foo/bar/**");
        assertThat(glob("foo").meterTag()).isEqualTo("glob:/**/foo");
    }

    @Test
    void params() throws Exception {
        Route route = glob("baz");
        assertThat(route.paramNames()).isEmpty();
        // Should not create a param for 'bar'
        assertThat(route.apply(create("/bar/baz")).pathParams()).isEmpty();

        route = glob("/bar/baz/*");
        assertThat(route.paramNames()).containsExactly("0");
        assertThat(route.apply(create("/bar/baz/qux")).pathParams())
                .containsEntry("0", "qux")
                .hasSize(1);

        route = glob("/foo/**");
        assertThat(route.paramNames()).containsExactly("0");
        assertThat(route.apply(create("/foo/bar/baz")).pathParams())
                .containsEntry("0", "bar/baz")
                .hasSize(1);
        assertThat(route.apply(create("/foo/")).pathParams())
                .containsEntry("0", "")
                .hasSize(1);

        route = glob("/**/*.js");
        assertThat(route.paramNames()).containsExactlyInAnyOrder("0", "1");
        assertThat(route.apply(create("/lib/jquery.min.js")).pathParams())
                .containsEntry("0", "lib")
                .containsEntry("1", "jquery.min")
                .hasSize(2);

        assertThat(route.apply(create("/lodash.js")).pathParams())
                .containsEntry("0", "")
                .containsEntry("1", "lodash")
                .hasSize(2);
    }

    @Test
    void utf8() throws Exception {
        final Route route = glob("/foo/*");
        final RoutingResult res = route.apply(create("/foo/%C2%A2"));
        assertThat(res.path()).isEqualTo("/foo/%C2%A2");
        assertThat(res.decodedPath()).isEqualTo("/foo/¢");
        assertThat(res.pathParams()).containsEntry("0", "¢").hasSize(1);
    }

    private static void pass(String glob, String... paths) {
        final Route route = glob(glob);
        for (String p : paths) {
            if (!route.apply(create(p)).isPresent()) {
                Assertions.fail('\'' + p + "' does not match '" + glob + "' or '" +
                                route.paths().get(0) + "'.");
            }
        }
    }

    private static void fail(String glob, String... paths) {
        final Route route = glob(glob);
        for (String p : paths) {
            if (route.apply(create(p)).isPresent()) {
                Assertions.fail('\'' + p + "' matches '" + glob + "' or '" + route.paths().get(0) + "'.");
            }
        }
    }

    private static Route glob(String glob) {
        return Route.builder().glob(glob).build();
    }
}
