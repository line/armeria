/*
 * Copyright 2020 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpMethod;

class MethodInfoTest {

    private static MethodInfo newMethodInfo(List<String> examplePaths, List<String> exampleQueries) {
        return new MethodInfo("foo", TypeSignature.ofBase("T"),
                              /* parameters */ ImmutableList.of(), /* exceptionSignatures */ ImmutableList.of(),
                              /* endpoints */ ImmutableList.of(), /* exampleHeaders */ ImmutableList.of(),
                              /* exampleRequests */ ImmutableList.of(),
                              examplePaths, exampleQueries,
                              HttpMethod.GET, null);
    }

    @Test
    void invalidQueryString() {
        final String invalidQuery = "?%x";
        assertThatThrownBy(() -> newMethodInfo(ImmutableList.of(), ImmutableList.of(invalidQuery)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains an invalid query string");
    }

    @Test
    void invalidPath() {
        final String invalidPath = "a/b";
        assertThatThrownBy(() -> newMethodInfo(ImmutableList.of(invalidPath), ImmutableList.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contains an invalid path");
    }

    @Test
    void validQueryString() {
        for (String queryString : ImmutableList.of("foo", "?foo", "??foo")) {
            assertThat(newMethodInfo(ImmutableList.of(), ImmutableList.of(queryString)).exampleQueries())
                    .containsOnly(queryString);
        }
    }
}
