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
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.Cookies;

class DefaultCookieJarTest {

    @Test
    void testGetSet() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI foo = URI.create("http://foo.com");
        final URI bar = URI.create("http://bar.com");

        cookieJar.set(foo, Cookies.of(Cookie.of("cookie1", "value1"),
                                      Cookie.of("cookie2", "value2")));
        assertThat(cookieJar.get(foo)).containsAll(
                Cookies.of(Cookie.builder("cookie1", "value1").path("/").build(),
                           Cookie.builder("cookie2", "value2").path("/").build())
        );
        assertThat(cookieJar.get(bar)).isEmpty();

        cookieJar.set(bar, Cookies.of(Cookie.of("cookie4", "value4"),
                                      Cookie.of("cookie5", "value5")));
        assertThat(cookieJar.get(bar)).containsAll(
                Cookies.of(Cookie.builder("cookie4", "value4").path("/").build(),
                           Cookie.builder("cookie5", "value5").path("/").build())
        );
        assertThat(cookieJar.get(foo)).doesNotContainAnyElementsOf(
                Cookies.of(Cookie.builder("cookie4", "value4").path("/").build(),
                           Cookie.builder("cookie5", "value5").path("/").build())
        );
    }

    @Test
    void testSecureCookie() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI fooHttp = URI.create("http://foo.com");
        final URI fooHttps = URI.create("https://foo.com");
        final Cookie cookie = Cookie.fromSetCookieHeader("name=value; secure");

        cookieJar.set(fooHttp, Cookies.of(cookie));
        assertThat(cookieJar.get(fooHttp)).isEmpty();

        cookieJar.set(fooHttps, Cookies.of(cookie));
        assertThat(cookieJar.get(fooHttp)).isEmpty();
        assertThat(cookieJar.get(fooHttps)).contains(
                Cookie.builder("name", "value").path("/").secure(true).build());
    }

    @Test
    void testCookiePath() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI foo = URI.create("http://foo.com");
        final URI foobar = URI.create("http://foo.com/bar");
        final Cookie cookie = Cookie.fromSetCookieHeader("name=value; path=/bar");

        cookieJar.set(foo, Cookies.of(cookie));
        assertThat(cookieJar.get(foo)).isEmpty();
        assertThat(cookieJar.get(foobar)).contains(Cookie.builder("name", "value").path("/bar").build());
    }

    @Test
    void testCookieDomain() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI foobar = URI.create("http://bar.foo.com");
        final URI foobaz = URI.create("http://baz.foo.com");
        final Cookie cookie1 = Cookie.fromSetCookieHeader("name1=value1");
        final Cookie cookie2 = Cookie.fromSetCookieHeader("name2=value2; domain=.foo.com");
        final Cookie cookie3 = Cookie.fromSetCookieHeader("name2=value2; domain=.com");

        cookieJar.set(foobar, Cookies.of(cookie1, cookie2, cookie3));
        cookieJar.set(foobaz, Cookies.of(cookie3));
        assertThat(cookieJar.get(foobaz))
                .hasSize(1)
                .contains(Cookie.builder("name2", "value2").path("/").domain(".foo.com").build());
    }

    @Test
    void testSetCookiePolicy() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI foo = URI.create("http://foo.com");

        cookieJar.setCookiePolicy(CookiePolicy.ACCEPT_NONE);
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).isNotEmpty();
    }

    @Test
    void testCookieMaxAge() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI foo = URI.create("http://foo.com");

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(1).build()));
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").build()));
        assertThat(cookieJar.get(foo)).hasSize(1);
        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(-1).build()));
        assertThat(cookieJar.get(foo)).isEmpty();
    }
}
