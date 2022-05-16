/*
 * Copyright 2019 LINE Corporation
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
package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DefaultCookieTest {

    @Test
    void toBuilder() {
        final Cookie cookie = Cookie.secureBuilder("a", "b")
                                    .domain("c")
                                    .path("/d")
                                    .valueQuoted(true)
                                    .build();
        assertThat(cookie.toBuilder().build()).isEqualTo(cookie);
    }

    @Test
    void mutation() {
        final Cookie cookie = Cookie.ofSecure("a", "b").withMutations(mutator -> mutator.name("c").value("d"));
        assertThat(cookie).isEqualTo(Cookie.ofSecure("c", "d"));
    }

    @Test
    void trimDomainDot() {
        Cookie cookie = Cookie.secureBuilder("a", "b").domain("foo.com.").build();
        assertThat(cookie.domain()).isNull();

        cookie = Cookie.secureBuilder("a", "b").domain(".foo.com").build();
        assertThat(cookie.domain()).isEqualTo("foo.com");
    }
}
