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

import static com.linecorp.armeria.server.RoutingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class RegexPathMappingTest {

    @Test
    void patternString() {
        final RegexPathMapping regexPathMapping1 = new RegexPathMapping(Pattern.compile("foo/bar"));
        assertThat(regexPathMapping1.patternString()).isEqualTo("foo/bar");

        final RegexPathMapping regexPathMapping2 =
                new RegexPathMapping(Pattern.compile("^/files/(?<fileName>.*)$"));
        assertThat(regexPathMapping2.patternString()).isEqualTo("^/files/(?<fileName>.*)$");
    }

    @Test
    void basic() {
        final RegexPathMapping regexPathMapping = new RegexPathMapping(Pattern.compile("foo"));
        final RoutingResult result = regexPathMapping.apply(create("/barfoobar")).build();
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/barfoobar");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    void pathParams() {
        final RegexPathMapping regexPathMapping =
                new RegexPathMapping(Pattern.compile("^/files/(?<fileName>.*)$"));
        assertThat(regexPathMapping.paramNames()).containsExactly("fileName");

        final RoutingResult result = regexPathMapping.apply(create("/files/images/avatar.jpg", "size=512"))
                                                     .build();
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/files/images/avatar.jpg");
        assertThat(result.query()).isEqualTo("size=512");
        assertThat(result.pathParams()).containsEntry("fileName", "images/avatar.jpg").hasSize(1);
    }

    @Test
    void utf8() {
        final RegexPathMapping regexPathMapping = new RegexPathMapping(Pattern.compile("^/(?<foo>.*)$"));
        final RoutingResult result = regexPathMapping.apply(create("/%C2%A2")).build();
        assertThat(result.path()).isEqualTo("/%C2%A2");
        assertThat(result.decodedPath()).isEqualTo("/¢");
        assertThat(result.pathParams()).containsEntry("foo", "¢").hasSize(1);
    }
}
