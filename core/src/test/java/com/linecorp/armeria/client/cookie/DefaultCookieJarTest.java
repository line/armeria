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

        cookieJar.set(foo, Cookies.of(Cookie.of("cookie1", "value1"), Cookie.of("cookie2", "value2")));

        assertThat(cookieJar.get(bar)).isEmpty();
        assertThat(cookieJar.get(foo)).hasSize(2);

        cookieJar.set(bar, Cookies.of(Cookie.of("cookie4", "value4"), Cookie.of("cookie5", "value5")));

        assertThat(cookieJar.get(bar)).hasSize(2);
        assertThat(cookieJar.get(foo)).hasSize(2).doesNotContainAnyElementsOf(Cookies.of(
                Cookie.builder("cookie4", "value4").domain("bar.com").path("/").build(),
                Cookie.builder("cookie5", "value5").domain("bar.com").path("/").build()));
    }

    @Test
    void testSecureCookie() {
        final CookieJar cookieJar = new DefaultCookieJar();
        final URI fooHttp = URI.create("http://foo.com");
        final URI fooHttps = URI.create("https://foo.com");
        final Cookie secureCookie = Cookie.fromSetCookieHeader("name=value; secure");

        cookieJar.set(fooHttp, Cookies.of(secureCookie));
        assertThat(cookieJar.get(fooHttp)).isEmpty();

        cookieJar.set(fooHttps, Cookies.of(secureCookie));
        assertThat(cookieJar.get(fooHttp)).isEmpty();
        assertThat(cookieJar.get(fooHttps)).hasSize(1);
    }

    @Test
    void testCookiePath() {
        final CookieJar cookieJar = new DefaultCookieJar();

        cookieJar.set(URI.create("http://foo.com"),
                      Cookies.of(Cookie.fromSetCookieHeader("name=value; path=/bar"),
                                 Cookie.fromSetCookieHeader("name=value; path=/boo")));

        assertThat(cookieJar.get(URI.create("http://foo.com"))).isEmpty();
        assertThat(cookieJar.get(URI.create("http://foo.com/bar"))).hasSize(1);
        assertThat(cookieJar.get(URI.create("http://foo.com/bar/baz"))).hasSize(1);
    }

    @Test
    void testCookieDomain() {
        final CookieJar cookieJar = new DefaultCookieJar();

        final Cookie cookie1 = Cookie.fromSetCookieHeader("name1=value1");
        final Cookie cookie2 = Cookie.fromSetCookieHeader("name2=value2; domain=.foo.com");
        final Cookie cookie3 = Cookie.fromSetCookieHeader("name3=value3; domain=foo.com");
        final Cookie cookie4 = Cookie.fromSetCookieHeader("name4=value4; domain=bar.foo.com");
        final Cookie cookie5 = Cookie.fromSetCookieHeader("name5=value5; domain=baz.foo.com");
        final Cookie cookie6 = Cookie.fromSetCookieHeader("name6=value6; domain=baz.bar.foo.com");

        cookieJar.set(URI.create("http://bar.foo.com"),
                      Cookies.of(cookie1, cookie2, cookie3, cookie4, cookie5, cookie6));

        assertThat(cookieJar.get(URI.create("http://baz.foo.com")))
                .hasSize(2)
                .containsAll(Cookies.of(
                        Cookie.builder("name2", "value2").domain(".foo.com").path("/").build(),
                        Cookie.builder("name3", "value3").domain("foo.com").path("/").build()));

        assertThat(cookieJar.get(URI.create("http://baz.bar.foo.com")))
                .hasSize(3)
                .containsAll(Cookies.of(
                        Cookie.builder("name2", "value2").domain(".foo.com").path("/").build(),
                        Cookie.builder("name3", "value3").domain("foo.com").path("/").build(),
                        Cookie.builder("name4", "value4").domain("bar.foo.com").path("/").build()));
    }

    @Test
    void testSetCookiePolicy() {
        final URI foo = URI.create("http://foo.com");

        CookieJar cookieJar = new DefaultCookieJar(CookiePolicy.ACCEPT_NONE);
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar = new DefaultCookieJar(CookiePolicy.ACCEPT_ALL);
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).hasSize(1);
    }

    @Test
    void testCookieMaxAge() {
        final URI foo = URI.create("http://foo.com");
        final CookieJar cookieJar = new DefaultCookieJar();

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(1).build()));
        await().pollDelay(Duration.ofSeconds(1)).until(() -> true);
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").build()));
        assertThat(cookieJar.get(foo)).hasSize(1);
        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(-1).build()));
        assertThat(cookieJar.get(foo)).isEmpty();
    }
}
