/* Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocServiceBuilderTest {

    @Test
    void invalidQueryString() {
        final String invalidQuery = "?%x";
        assertThatThrownBy(() -> DocService.builder()
                                           .exampleQueries("Foo", "bar", invalidQuery))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contain an invalid query string");
    }

    @Test
    void invalidPath() {
        final String invalidPath = "a/b";
        assertThatThrownBy(() -> DocService.builder()
                                           .examplePaths("Foo", "bar", invalidPath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contain an invalid path");
    }
}
