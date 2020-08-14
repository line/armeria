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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PrefixPathMappingTest {

    @Test
    void patternString() {
        final PrefixPathMapping prefixPathMapping1 = new PrefixPathMapping("/foo/bar", true);
        assertThat(prefixPathMapping1.patternString()).isEqualTo("/foo/bar/*");

        final PrefixPathMapping prefixPathMapping2 = new PrefixPathMapping("/foo/bar/", true);
        assertThat(prefixPathMapping2.patternString()).isEqualTo("/foo/bar/*");
    }

    @Test
    void routingResult() {
        final PrefixPathMapping prefixPathMapping = new PrefixPathMapping("/foo", true);
        final RoutingResult result = prefixPathMapping.apply(create("/foo/bar/cat")).build();
        assertThat(result.path()).isEqualTo("/bar/cat");
    }

    @Test
    void equality() {
        final PrefixPathMapping a = new PrefixPathMapping("/foo", true);
        final PrefixPathMapping b = new PrefixPathMapping("/bar", true);
        final PrefixPathMapping c = new PrefixPathMapping("/foo", true);

        Assumptions.assumeTrue(a != c);
        assertThat(a).isEqualTo(c);
        assertThat(a).isNotEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(c.hashCode());
        assertThat(a.hashCode()).isNotEqualTo(b.hashCode());
    }

    @Test
    void pathParams() {
        final PrefixPathMapping prefixPathMapping = new PrefixPathMapping("/bar/baz", true);
        assertThat(prefixPathMapping.paramNames()).isEmpty();
    }
}
