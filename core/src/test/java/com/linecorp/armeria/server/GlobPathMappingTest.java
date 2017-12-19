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

import static com.linecorp.armeria.server.PathMapping.ofGlob;
import static com.linecorp.armeria.server.PathMappingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Assert;
import org.junit.Test;

public class GlobPathMappingTest {

    @Test
    public void testSingleAsterisk() {
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
    public void testDoubleAsterisk() {
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
    public void testRelativePattern() {
        pass("baz", "/baz", "/bar/baz", "/foo/bar/baz");
        fail("baz", "/baz/", "/bar/baz/", "/foo/bar/baz/", "/foo/bar/baz/quo");

        pass("bar/baz", "/bar/baz", "/foo/bar/baz");
        fail("bar/baz", "/bar/baz/", "/foo/bar/baz/", "/foo/bar/baz/quo");
    }

    @Test(expected = IllegalArgumentException.class)
    public void testPathValidation() {
        compile("**").apply(create("not/an/absolute/path"));
    }

    @Test
    public void testLoggerName() throws Exception {
        assertThat(ofGlob("/foo/bar/**").loggerName()).isEqualTo("foo.bar.__");
        assertThat(ofGlob("foo").loggerName()).isEqualTo("__.foo");
    }

    @Test
    public void testMetricName() throws Exception {
        assertThat(ofGlob("/foo/bar/**").meterTag()).isEqualTo("glob:/foo/bar/**");
        assertThat(ofGlob("foo").meterTag()).isEqualTo("glob:/**/foo");
    }

    @Test
    public void params() throws Exception {
        PathMapping m;

        m = ofGlob("baz");
        assertThat(m.paramNames()).isEmpty();
        // Should not create a param for 'bar'
        assertThat(m.apply(create("/bar/baz")).pathParams()).isEmpty();

        m = ofGlob("/bar/baz/*");
        assertThat(m.paramNames()).containsExactly("0");
        assertThat(m.apply(create("/bar/baz/qux")).pathParams())
                .containsEntry("0", "qux")
                .hasSize(1);

        m = ofGlob("/foo/**");
        assertThat(m.paramNames()).containsExactly("0");
        assertThat(m.apply(create("/foo/bar/baz")).pathParams())
                .containsEntry("0", "bar/baz")
                .hasSize(1);
        assertThat(m.apply(create("/foo/")).pathParams())
                .containsEntry("0", "")
                .hasSize(1);

        m = ofGlob("/**/*.js");
        assertThat(m.paramNames()).containsExactlyInAnyOrder("0", "1");
        assertThat(m.apply(create("/lib/jquery.min.js")).pathParams())
                .containsEntry("0", "lib")
                .containsEntry("1", "jquery.min")
                .hasSize(2);

        assertThat(m.apply(create("/lodash.js")).pathParams())
                .containsEntry("0", "")
                .containsEntry("1", "lodash")
                .hasSize(2);
    }

    private static void pass(String glob, String... paths) {
        final GlobPathMapping pattern = compile(glob);
        for (String p: paths) {
            if (!pattern.apply(create(p)).isPresent()) {
                Assert.fail('\'' + p + "' does not match '" + glob + "' or '" + pattern.asRegex() + "'.");
            }
        }
    }

    private static void fail(String glob, String... paths) {
        final GlobPathMapping pattern = compile(glob);
        for (String p: paths) {
            if (pattern.apply(create(p)).isPresent()) {
                Assert.fail('\'' + p + "' matches '" + glob + "' or '" + pattern.asRegex() + "'.");
            }
        }
    }

    private static GlobPathMapping compile(String glob) {
        return (GlobPathMapping) ofGlob(glob);
    }
}
