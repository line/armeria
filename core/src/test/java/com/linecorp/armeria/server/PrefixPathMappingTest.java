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

import static com.linecorp.armeria.server.PathMapping.ofPrefix;
import static com.linecorp.armeria.server.PathMappingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.Assume.assumeTrue;

import org.junit.Test;

public class PrefixPathMappingTest {

    @Test
    public void testLoggerName() throws Exception {
        assertThat(ofPrefix("/foo/bar").loggerName()).isEqualTo("foo.bar");
    }

    @Test
    public void testMetricName() throws Exception {
        assertThat(ofPrefix("/foo/bar").meterTag()).isEqualTo("prefix:/foo/bar/");
    }

    @Test
    public void mappingResult() {
        final PathMapping a = ofPrefix("/foo");
        PathMappingResult result = a.apply(create("/foo/bar/cat"));
        assertThat(result.path()).isEqualTo("/bar/cat");
    }

    @Test
    public void equality() {
        final PathMapping a = ofPrefix("/foo");
        final PathMapping b = ofPrefix("/bar");
        final PathMapping c = ofPrefix("/foo");

        assumeTrue(a != c);
        assertThat(a).isEqualTo(c);
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(c.hashCode());
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    public void pathParams() {
        assertThat(ofPrefix("/bar/baz").paramNames()).isEmpty();
    }
}
