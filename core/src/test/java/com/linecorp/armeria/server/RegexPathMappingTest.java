/*
 * Copyright 2016 LINE Corporation
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

import static com.linecorp.armeria.server.PathMapping.ofRegex;
import static com.linecorp.armeria.server.PathMappingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class RegexPathMappingTest {
    @Test
    public void testLoggerName() throws Exception {
        assertThat(ofRegex("foo/bar").loggerName()).isEqualTo("regex.foo_bar");
    }

    @Test
    public void testMetricName() throws Exception {
        assertThat(ofRegex("foo/bar").meterTag()).isEqualTo("regex:foo/bar");
    }

    @Test
    public void basic() {
        final PathMapping mapping = ofRegex("foo");
        final PathMappingResult result = mapping.apply(create("/barfoobar"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/barfoobar");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    public void pathParams() {
        final PathMapping mapping = ofRegex("^/files/(?<fileName>.*)$");
        assertThat(mapping.paramNames()).containsExactly("fileName");

        final PathMappingResult result = mapping.apply(create("/files/images/avatar.jpg", "size=512"));
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/files/images/avatar.jpg");
        assertThat(result.query()).isEqualTo("size=512");
        assertThat(result.pathParams()).containsEntry("fileName", "images/avatar.jpg").hasSize(1);
    }
}
