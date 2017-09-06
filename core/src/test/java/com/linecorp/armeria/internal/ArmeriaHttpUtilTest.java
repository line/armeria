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

package com.linecorp.armeria.internal;

import static com.linecorp.armeria.internal.ArmeriaHttpUtil.concatPaths;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.Test;

public class ArmeriaHttpUtilTest {
    @Test
    public void testConcatPaths() throws Exception {
        assertThat(concatPaths(null, "a")).isEqualTo("/a");
        assertThat(concatPaths(null, "/a")).isEqualTo("/a");

        assertThat(concatPaths("", "a")).isEqualTo("/a");
        assertThat(concatPaths("", "/a")).isEqualTo("/a");

        assertThat(concatPaths("/", "a")).isEqualTo("/a");
        assertThat(concatPaths("/", "/a")).isEqualTo("/a");

        assertThat(concatPaths("/a", "b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a", "/b")).isEqualTo("/a/b");
        assertThat(concatPaths("/a/", "/b")).isEqualTo("/a/b");
    }
}
