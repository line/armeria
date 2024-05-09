/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.security.Security;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.netty.handler.ssl.SslContextBuilder;

class MinifiedBouncyCastleProviderTest {

    @BeforeEach
    void ensureNoBouncyCastle() {
        assertThat(Security.getProviders()).doesNotHaveAnyElementsOfTypes(BouncyCastleProvider.class,
                                                                          MinifiedBouncyCastleProvider.class);
    }

    @Test
    void relocatedPackagePrefix() {
        //noinspection ConstantValue - The assumption will meet when the test is run with shading enabled.
        assumeThat(BouncyCastleProvider.class.getName().contains(".internal.shaded.")).isTrue();
        assertThat(MinifiedBouncyCastleProvider.BC_PACKAGE_PREFIX)
                .isEqualTo("com.linecorp.armeria.internal.shaded.bouncycastle.");
    }

    @Test
    void classNameShouldBeChecked() {
        final MinifiedBouncyCastleProvider provider = new MinifiedBouncyCastleProvider();
        assertThatThrownBy(() -> provider.addAlgorithm("foo", "incorrect.bouncycastle.class.name"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unexpected Bouncy Castle class name");
    }

    @Test
    void callIsCalled() {
        final AtomicBoolean called = new AtomicBoolean();

        // Test call(Runnable):
        MinifiedBouncyCastleProvider.call(() -> {
            assertMinifiedBouncyCastleProviderExists();
            called.set(true);
        });
        assertThat(called).isTrue();

        // Test call(Callable):
        assertThat(MinifiedBouncyCastleProvider.call(() -> {
            assertMinifiedBouncyCastleProviderExists();
            return 42;
        })).isEqualTo(42);
    }

    /**
     * Tests if a SSLeay PKCS#5 private key is accepted.
     */
    @Test
    void pkcs5() {
        MinifiedBouncyCastleProvider.call(this::loadPkcs5);
    }

    /**
     * Tests if a PKCS#8 private key is accepted.
     */
    @Test
    void pkcs8() {
        MinifiedBouncyCastleProvider.call(this::loadPkcs8);
    }

    /**
     * Tests if everything works even if Bouncy Castle is loaded already.
     */
    @Test
    void bouncyCastlePreInstalled() {
        Security.addProvider(new BouncyCastleProvider());
        try {
            MinifiedBouncyCastleProvider.call(this::loadPkcs5);
            MinifiedBouncyCastleProvider.call(this::loadPkcs8);
        } finally {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME);
        }
    }

    @Test
    void nestedInvocation() {
        MinifiedBouncyCastleProvider.call(() -> MinifiedBouncyCastleProvider.call(this::loadPkcs5));
    }

    private void loadPkcs5() {
        loadKey("/testing/core/ServerBuilderTest/pkcs5.pem");
    }

    private void loadPkcs8() {
        loadKey("/testing/core/ServerBuilderTest/pkcs8.pem");
    }

    private void loadKey(String privateKeyPath) {
        SslContextBuilder.forServer(getClass().getResourceAsStream("/testing/core/ServerBuilderTest/cert.pem"),
                                    getClass().getResourceAsStream(privateKeyPath),
                                    null);
    }

    private static void assertMinifiedBouncyCastleProviderExists() {
        assertThat(Security.getProviders()).hasAtLeastOneElementOfType(MinifiedBouncyCastleProvider.class);
    }
}
