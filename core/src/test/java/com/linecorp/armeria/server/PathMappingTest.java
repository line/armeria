/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class PathMappingTest {

    @Test
    public void successfulOf() {
        PathMapping m;

        m = PathMapping.of("/foo");
        assertThat(m).isInstanceOf(ExactPathMapping.class);
        assertThat(m.exactPath()).contains("/foo");

        m = PathMapping.of("/foo/{bar}");
        assertThat(m).isInstanceOf(DefaultPathMapping.class);
        assertThat(((DefaultPathMapping) m).skeleton()).isEqualTo("/foo/:");

        m = PathMapping.of("/bar/:baz");
        assertThat(m).isInstanceOf(DefaultPathMapping.class);
        assertThat(((DefaultPathMapping) m).skeleton()).isEqualTo("/bar/:");

        m = PathMapping.of("exact:/:foo/bar");
        assertThat(m).isInstanceOf(ExactPathMapping.class);
        assertThat(m.exactPath()).contains("/:foo/bar");

        m = PathMapping.of("prefix:/");
        assertThat(m).isInstanceOf(CatchAllPathMapping.class);

        m = PathMapping.of("prefix:/bar/baz");
        assertThat(m).isInstanceOf(PrefixPathMapping.class);
        assertThat(m.prefix()).contains("/bar/baz/");

        m = PathMapping.of("glob:/foo/bar");
        assertThat(m).isInstanceOf(ExactPathMapping.class);
        assertThat(m.exactPath()).contains("/foo/bar");

        m = PathMapping.of("glob:/home/*/files/**");
        assertThat(m).isInstanceOf(GlobPathMapping.class);
        assertThat(((GlobPathMapping) m).asRegex().pattern()).isEqualTo("^/home/([^/]+)/files/(.*)$");

        m = PathMapping.of("glob:foo");
        assertThat(m).isInstanceOf(GlobPathMapping.class);
        assertThat(((GlobPathMapping) m).asRegex().pattern()).isEqualTo("^/(?:.+/)?foo$");

        m = PathMapping.of("regex:^/files/(?<filePath>.*)$");
        assertThat(m).isInstanceOf(RegexPathMapping.class);
        assertThat(((RegexPathMapping) m).asRegex().pattern()).isEqualTo("^/files/(?<filePath>.*)$");
    }

    @Test
    public void failedOf() {
        assertThatThrownBy(() -> PathMapping.of("foo"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> PathMapping.of("foo:/bar"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
