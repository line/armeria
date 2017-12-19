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

import static com.linecorp.armeria.server.PathMapping.ofExact;
import static com.linecorp.armeria.server.PathMappingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ExactPathMappingTest {

    @Test
    public void shouldReturnEmptyOnMismatch() {
        final PathMappingResult result = new ExactPathMapping("/find/me").apply(create("/find/me/not"));
        assertThat(result.isPresent()).isFalse();
    }

    @Test
    public void shouldReturnNonEmptyOnMatch() {
        final PathMappingResult result = new ExactPathMapping("/find/me").apply(create("/find/me"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/find/me");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    public void testLoggerNameEscaping() throws Exception {
        assertThat(ofExact("/foo/bar.txt").loggerName()).isEqualTo("foo.bar_txt");
        assertThat(ofExact("/bar/b-a-z").loggerName()).isEqualTo("bar.b_a_z");
        assertThat(ofExact("/bar/baz/").loggerName()).isEqualTo("bar.baz");
    }

    @Test
    public void testLoggerName() throws Exception {
        assertThat(ofExact("/foo/bar").loggerName()).isEqualTo("foo.bar");
    }

    @Test
    public void testMetricName() throws Exception {
        assertThat(ofExact("/foo/bar").meterTag()).isEqualTo("exact:/foo/bar");
    }
}
