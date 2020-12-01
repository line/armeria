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

import static com.linecorp.armeria.server.RoutingContextTest.create;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExactPathMappingTest {

    @Test
    void shouldReturnNullOnMismatch() {
        final RoutingResultBuilder builder = new ExactPathMapping("/find/me").apply(create("/find/me/not"));
        assertThat(builder).isNull();
    }

    @Test
    void shouldReturnNonEmptyOnMatch() {
        final RoutingResult result = new ExactPathMapping("/find/me").apply(create("/find/me")).build();
        assertThat(result.isPresent()).isTrue();
        assertThat(result.path()).isEqualTo("/find/me");
        assertThat(result.query()).isNull();
        assertThat(result.pathParams()).isEmpty();
    }

    @Test
    void patternString() {
        final ExactPathMapping exactPathMapping = new ExactPathMapping("/foo/bar");
        assertThat(exactPathMapping.patternString()).isEqualTo("/foo/bar");
    }
}
