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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.Test;

public class PathMappedTest {
    /**
     * Should not accept an empty {@link PathMappingResult} when creating a non-empty {@link PathMapped}.
     */
    @Test
    public void shouldNotAcceptEmptyResult() {
        assertThatThrownBy(() -> PathMapped.of(PathMapping.ofCatchAll(),
                                               PathMappingResult.empty(),
                                               new Object()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void empty() {
        final PathMapped<?> mapped = PathMapped.empty();
        assertThat(mapped.isPresent()).isFalse();
        assertThatThrownBy(mapped::mapping).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(mapped::mappingResult).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(mapped::value).isInstanceOf(IllegalStateException.class);
    }
}
