/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.PathMapping.ofCatchAll;
import static com.linecorp.armeria.server.PathMapping.ofExact;
import static com.linecorp.armeria.server.PathMapping.ofGlob;
import static com.linecorp.armeria.server.PathMapping.ofPrefix;
import static com.linecorp.armeria.server.PathMapping.ofRegex;
import static com.linecorp.armeria.server.ServiceConfig.defaultLoggerName;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

import org.junit.Test;

public class ServiceConfigTest {

    @Test
    public void testDefaultLoggerNameEscaping() throws Exception {
        assertThat(defaultLoggerName(ofExact("/foo/bar.txt")), is("foo.bar_txt"));
        assertThat(defaultLoggerName(ofExact("/bar/b-a-z")), is("bar.b_a_z"));
        assertThat(defaultLoggerName(ofExact("/bar/baz/")), is("bar.baz"));
    }

    @Test
    public void testDefaultLoggerNameForExact() throws Exception {
        assertThat(defaultLoggerName(ofExact("/foo/bar")), is("foo.bar"));
    }

    @Test
    public void testDefaultLoggerNameForPrefix() throws Exception {
        assertThat(defaultLoggerName(ofPrefix("/foo/bar")), is("foo.bar"));
    }

    @Test
    public void testDefaultLoggerNameForGlob() throws Exception {
        assertThat(defaultLoggerName(ofGlob("/foo/bar/**")), is("__UNKNOWN__"));
    }

    @Test
    public void testDefaultLoggerNameForRegex() throws Exception {
        assertThat(defaultLoggerName(ofRegex("foo/bar")), is("__UNKNOWN__"));
    }

    @Test
    public void testDefaultLoggerNameForCatchAll() throws Exception {
        assertThat(defaultLoggerName(ofCatchAll()), is("__ROOT__"));
    }
}
