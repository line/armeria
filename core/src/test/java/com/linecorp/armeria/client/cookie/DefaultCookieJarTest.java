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
import com.linecorp.armeria.common.CookieBuilder;
import com.linecorp.armeria.common.Cookies;

class DefaultCookieJarTest {

    @Test
    void testPublicTLD() {
        final DefaultCookieJar cookieJar = new DefaultCookieJar();

        cookieJar.set(URI.create("http://google.com"),
                      Cookies.of(Cookie.builder("name", "value").domain("com").build()));
        assertThat(cookieJar.get(URI.create("http://google.com"))).isEmpty();

        cookieJar.set(URI.create("http://foo.kawasaki.jp"),
                      Cookies.of(Cookie.builder("name", "value").domain("kawasaki.jp").build()));
        assertThat(cookieJar.get(URI.create("http://foo.kawasaki.jp"))).isEmpty();

        cookieJar.set(URI.create("http://foo.city.kawasaki.jp"),
                      Cookies.of(Cookie.builder("name", "value").domain("city.kawasaki.jp").build()));
        assertThat(cookieJar.get(URI.create("http://city.kawasaki.jp"))).hasSize(1);

        cookieJar.set(URI.create("http://xn--12c1fe0br.xn--o3cw4h"),
                      Cookies.of(Cookie.builder("name", "value").domain("xn--12c1fe0br.xn--o3cw4h").build()));
        assertThat(cookieJar.get(URI.create("http://xn--12c1fe0br.xn--o3cw4h"))).isEmpty();
    }

    @Test
    void testCookieDefaultDomainAndPath() {
        final DefaultCookieJar cookieJar = new DefaultCookieJar();
        final Cookie emptyCookie = Cookie.of("", "");
        final CookieBuilder emptyBuilder = Cookie.builder("", "");

        assertThat(cookieJar.ensureDomainAndPath(emptyCookie, URI.create("http://foo.com")))
                .isEqualTo(emptyBuilder.domain("foo.com").path("/").build());

        assertThat(cookieJar.ensureDomainAndPath(emptyCookie, URI.create("http://bar.foo.com/")))
                .isEqualTo(emptyBuilder.domain("bar.foo.com").path("/").build());

        assertThat(cookieJar.ensureDomainAndPath(emptyCookie, URI.create("http://bar.foo.com/a/b")))
                .isEqualTo(emptyBuilder.domain("bar.foo.com").path("/a").build());

        assertThat(cookieJar.ensureDomainAndPath(emptyCookie, URI.create("http://foo.com/a/b/")))
                .isEqualTo(emptyBuilder.domain("foo.com").path("/a/b").build());

        // domain and path are unchanged if already set
        assertThat(cookieJar.ensureDomainAndPath(emptyBuilder.domain("foo.com").path("/a").build(),
                                                 URI.create(("http://bar.foo.com/a/b/"))))
                .isEqualTo(emptyBuilder.domain("foo.com").path("/a").build());

        assertThat(cookieJar.ensureDomainAndPath(emptyCookie,
                                                 URI.create("http://foo.com")).isHostOnly()).isTrue();

        assertThat(cookieJar.ensureDomainAndPath(emptyBuilder.domain("foo.com").build(),
                                                 URI.create("http://foo.com")).isHostOnly()).isFalse();
    }

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
    void testCookieSecure() {
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
    void testCookieMaxAge() {
        final URI foo = URI.create("http://foo.com");
        final CookieJar cookieJar = new DefaultCookieJar();

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(1).build()));
        await().pollDelay(Duration.ofSeconds(2)).until(() -> true);
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").build()));
        assertThat(cookieJar.get(foo)).hasSize(1);
        cookieJar.set(foo, Cookies.of(Cookie.builder("name", "value").maxAge(-1).build()));
        assertThat(cookieJar.get(foo)).isEmpty();
    }

    @Test
    void testCookiePolicy() {
        final URI foo = URI.create("http://foo.com");

        CookieJar cookieJar = new DefaultCookieJar(CookiePolicy.acceptNone());
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).isEmpty();

        cookieJar = new DefaultCookieJar(CookiePolicy.acceptAll());
        cookieJar.set(foo, Cookies.of(Cookie.of("name", "value")));
        assertThat(cookieJar.get(foo)).hasSize(1);
    }
}
