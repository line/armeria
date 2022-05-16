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

package com.linecorp.armeria.client.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.CookieBuilder;

class AcceptOriginCookiePolicyTest {

    private final AcceptOriginCookiePolicy policy = AcceptOriginCookiePolicy.get();

    @Test
    void accept() {
        final CookieBuilder builder = Cookie.secureBuilder("name", "value");

        assertThat(policy.accept(URI.create("foo.com"), Cookie.ofSecure("name", "value"))).isFalse();
        assertThat(policy.accept(URI.create("foo.com"), builder.domain("foo.com").build())).isFalse();
        assertThat(policy.accept(URI.create("http://foo.com"), Cookie.ofSecure("name", "value"))).isFalse();
        assertThat(policy.accept(URI.create("http://foo.com"), builder.domain("foo.com").build())).isTrue();

        final URI google = URI.create("http://google.com");
        final URI docs = URI.create("http://docs.google.com");
        assertThat(policy.accept(google, builder.domain("com").build())).isFalse();
        assertThat(policy.accept(google, builder.domain("googlee.com").build())).isFalse();
        assertThat(policy.accept(google, builder.domain(".google.com").build())).isTrue();

        assertThat(policy.accept(google, builder.domain("docs.google.com").build())).isFalse();
        assertThat(policy.accept(docs, builder.domain("google.com").build())).isTrue();

        assertThat(policy.accept(URI.create("http://8.8.8.8"),
                                 builder.domain("google.com").build())).isFalse();
        assertThat(policy.accept(URI.create("http://2001:4860:4860::8888"),
                                 builder.domain("google.com").build())).isFalse();
    }
}
