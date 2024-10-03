/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SchemeAndAuthorityTest {
    @ParameterizedTest
    @CsvSource({
            "0.0.0.0,           0.0.0.0,             0.0.0.0,            -1",    // IPv4
            "0.0.0.0:,          0.0.0.0:,            0.0.0.0,            -1",    // IPv4 with empty port
            "0.0.0.0:80,        0.0.0.0:80,          0.0.0.0,            80",    // IPv4 with port
            "[::1],             [::1],               [::1],              -1",    // IPv6
            "[::1]:,            [::1]:,              [::1],              -1",    // IPv6 with empty port
            "[::1]:8080,        [::1]:8080,          [::1],              8080",  // IPv6 with port
            "[::1%eth0]:8080,   [::1]:8080,          [::1],              8080",  // IPv6 with port and scope
            "unix%3Afoo.sock,   unix%3Afoo.sock,     unix%3Afoo.sock,    -1",    // Domain socket
            "foo.bar,           foo.bar,             foo.bar,            -1",    // Only host
            "foo:,              foo:,                foo,                -1",    // Empty port
            "bar:80,            bar:80,              bar,                80",    // Host and port
            "foo@bar:80,        bar:80,              bar,                80",    // Userinfo and host and port
            "foo_bar:80,        foo_bar:80,          foo_bar,            80",    // Underscore in host
            "한글.com:80,        xn--bj0bj06e.com:80, xn--bj0bj06e.com,   80",    // IDN
    })
    void fromAuthority(String authority, String expectedAuthority, String expectedHost,
                       int expectedPort) {
        assertThat(SchemeAndAuthority.of(null, authority)).satisfies(uri -> {
            assertThat(uri.scheme()).isNull();
            assertThat(uri.authority()).isEqualTo(expectedAuthority);
            assertThat(uri.host()).isEqualTo(expectedHost);
            assertThat(uri.port()).isEqualTo(expectedPort);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "foo:bar",                 // Invalid port
            "http://foo:80",           // Scheme included
            "foo/bar",                 // Authority with path
            "foo?bar=1",               // Authority with query
            "foo#bar",                 // Authority with fragment
            "[192.168.0.1]",           // Bracketed IPv4
            "[::1", "::1]",            // Incomplete IPv6
            "[::1%eth0", "::1%eth0]",  // Incomplete IPv6 with scope
            "[::1]%eth0",              // Invalid IPv6 scope
            "unix:foo.sock"            // Invalid domain socket
    })
    void fromBadAuthority(String badAuthority) {
        assertThatThrownBy(() -> SchemeAndAuthority.of(null, badAuthority))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "http, http", "https, https", "ftp, ftp", "mailto, mailto", "file, file", "tel, tel", "ssh, ssh",
            "htTP, http", "HTTP, http", "http+none, http+none"
    })
    void fromScheme(String scheme, String expectedScheme) {
        assertThat(SchemeAndAuthority.of(scheme, "foo")).satisfies(uri -> {
            assertThat(uri.scheme()).isEqualTo(expectedScheme);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "1http", "+http", ".http", "-http", "http!", "http$", "http?", "http#", "http ftp"
    })
    void fromBadScheme(String badScheme) {
        assertThatThrownBy(() -> SchemeAndAuthority.of(badScheme, "foo"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
