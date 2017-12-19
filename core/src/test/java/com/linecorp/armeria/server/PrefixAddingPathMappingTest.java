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

import org.junit.Test;

import com.linecorp.armeria.server.AnnotatedHttpServices.PrefixAddingPathMapping;

public class PrefixAddingPathMappingTest {

    @Test
    public void testLoggerName() {
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofGlob("/bar/**")).loggerName())
                .isEqualTo("foo.bar.__");
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofGlob("bar")).loggerName())
                .isEqualTo("foo.__.bar");
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofRegex("/(foo|bar)")).loggerName())
                .isEqualTo("foo.regex.__foo_bar_");
    }

    @Test
    public void testMetricName() {
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofGlob("/bar/**")).meterTag())
                .isEqualTo("prefix:/foo/,glob:/bar/**");
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofGlob("bar")).meterTag())
                .isEqualTo("prefix:/foo/,glob:/**/bar");
        assertThat(new PrefixAddingPathMapping("/foo/", PathMapping.ofRegex("/(foo|bar)")).meterTag())
                .isEqualTo("prefix:/foo/,regex:/(foo|bar)");
    }
}
