/*
 * Copyright 2023 LINE Corporation
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
package com.linecorp.armeria.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

class DomainSocketAddressTest {
    @Test
    void authority() {
        assertThat(DomainSocketAddress.of(Paths.get("/var/run/test.sock"))).satisfies(addr -> {
            assertThat(addr.authority()).isEqualTo(
                    SystemInfo.osType() == OsType.WINDOWS ? "unix%3A%5Cvar%5Crun%5Ctest.sock"
                                                          : "unix%3A%2Fvar%2Frun%2Ftest.sock");
        });

        if (SystemInfo.osType() != OsType.WINDOWS) {
            // A path with a colon (:)
            assertThat(DomainSocketAddress.of(Paths.get("/foo:bar"))).satisfies(addr -> {
                assertThat(addr.authority()).isEqualTo("unix%3A%2Ffoo%3Abar");
            });
        }

        // A path with an at-sign (@)
        assertThat(DomainSocketAddress.of(Paths.get("/foo@bar"))).satisfies(addr -> {
            assertThat(addr.authority()).isEqualTo(
                    SystemInfo.osType() == OsType.WINDOWS ? "unix%3A%5Cfoo%40bar"
                                                          : "unix%3A%2Ffoo%40bar");
        });
    }

    @Test
    void asEndpoint() {
        final DomainSocketAddress addr = DomainSocketAddress.of(Paths.get("/var/run/test.sock"));
        assertThat(addr.asEndpoint()).satisfies(e -> {
            assertThat(e.isDomainSocket()).isTrue();
            assertThat(e.authority()).isEqualTo(
                    SystemInfo.osType() == OsType.WINDOWS ? "unix%3A%5Cvar%5Crun%5Ctest.sock"
                                                          : "unix%3A%2Fvar%2Frun%2Ftest.sock");
            assertThat(e.toSocketAddress(0)).isEqualTo(addr);
        });
    }

    @Test
    void abstractNamespace() {
        final DomainSocketAddress addr = DomainSocketAddress.of("\0f\0o");
        assertThat(addr.path()).isEqualTo("\0f\0o");
        assertThat(addr.isAbstract()).isTrue();
        assertThat(addr.authority()).isEqualTo("unix%3A%00f%00o");
        assertThat(addr).hasToString("@f@o");
        assertThat(addr.asEndpoint()).satisfies(e -> {
            assertThat(e.isDomainSocket()).isTrue();
            assertThat(e.authority()).isEqualTo("unix%3A%00f%00o");
            assertThat(e.toSocketAddress(0)).isEqualTo(addr);
        });
    }
}
