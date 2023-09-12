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
package com.linecorp.armeria.internal.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.File;
import java.net.URL;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.KeyStoreUtil.KeyPair;

class KeyStoreUtilTest {
    // The key store files used in this test case were generated with the following commands:
    //
    // 1. Generate self-signed certificate pairs:
    //
    //    $ openssl req -newkey rsa:2048 -x509 -sha256 -days 365 -nodes \
    //                  -out foo.com.crt -keyout foo.com.key -subj "/CN=foo.com"
    //    $ openssl req -newkey rsa:2048 -x509 -sha256 -days 365 -nodes \
    //                  -out bar.com.crt -keyout bar.com.key -subj "/CN=bar.com"
    //
    // 2. Create the key stores from the above certificate pair:
    //
    //    $ openssl pkcs12 -export -out keystore.p12 \
    //                     -inkey foo.com.key -in foo.com.crt -name first
    //    ... Enter 'my-first-password' ...
    //
    //    $ openssl pkcs12 -export -out keystore-two-keys.p12 \
    //                     -inkey bar.com.key -in bar.com.crt -name second
    //    ... Enter 'my-second-password' ...
    //
    //    $ keytool -importkeystore -srckeystore keystore.p12 -srcstoretype pkcs12 \
    //              -destkeystore keystore-two-keys.p12 -deststoretype pkcs12
    //    ... Enter 'my-second-password' and then 'my-first-password' ...
    //
    //    $ keytool -importkeystore -srckeystore keystore.p12 -srcstoretype PKCS12 \
    //              -destkeystore keystore.jks -deststoretype JKS
    //    ... Enter 'my-first-password' three times ...
    //
    //    $ cp keystore.jks keystore-different-password.jks
    //    $ keytool -keypasswd -alias first -keystore keystore-different-password.jks
    //    ... Enter 'my-first-password' and then 'my-key-password' twice ...
    //
    // 3. Delete the cruft.
    //
    //    $ rm foo.com.crt foo.com.key bar.com.crt bar.com.key
    //
    @ParameterizedTest
    @CsvSource({
            "keystore.p12, my-first-password, _",
            "keystore.jks, my-first-password, _",
            "keystore-different-password.jks, my-first-password, my-key-password",
    })
    void shouldLoadKeyStoreWithOneKeyPair(String filename,
                                          @Nullable String keyStorePassword,
                                          @Nullable String keyPassword) throws Exception {
        final KeyPair keyPair = KeyStoreUtil.load(getFile(filename),
                                                  underscoreToNull(keyStorePassword),
                                                  underscoreToNull(keyPassword),
                                                  null /* no alias */);
        assertThat(keyPair.certificateChain()).hasSize(1).allSatisfy(cert -> {
            assertThat(cert.getSubjectX500Principal().getName()).isEqualTo("CN=foo.com");
        });
    }

    @ParameterizedTest
    @CsvSource({"first, foo.com", "second, bar.com"})
    void shouldLoadKeyStoreWithTwoKeyPairsIfAliasIsGiven(String alias, String expectedCN) throws Exception {
        final KeyPair keyPair = KeyStoreUtil.load(getFile("keystore-two-keys.p12"),
                                                  "my-second-password",
                                                  null,
                                                  alias);

        assertThat(keyPair.certificateChain()).hasSize(1).allSatisfy(cert -> {
            assertThat(cert.getSubjectX500Principal().getName()).isEqualTo("CN=" + expectedCN);
        });
    }

    @Test
    void shouldFailWithTwoKeyPairsIfAliasDoesNotMatch() throws Exception {
        final File file = getFile("keystore-two-keys.p12");
        final String password = "my-second-password";
        assertThatThrownBy(() -> KeyStoreUtil.load(file, password, null, "bad-alias"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no key pair found");
        assertThatThrownBy(() -> KeyStoreUtil.load(file, password, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than one key pair");
    }

    @ParameterizedTest
    @CsvSource({"not-keystore.p12", "not-keystore-empty.p12"})
    void shouldFailIfFileIsNeitherJksNorPkcs12(String filename) throws Exception {
        assertThatThrownBy(() -> KeyStoreUtil.load(getFile(filename), null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown key store format");
    }

    private File getFile(String filename) {
        final URL url = getClass().getClassLoader().getResource(
                "testing/core/" + KeyStoreUtilTest.class.getSimpleName() + '/' + filename);
        assertThat(url).isNotNull();
        return new File(url.getFile());
    }

    @Nullable
    private static String underscoreToNull(@Nullable String value) {
        return "_".equals(value) ? null : value;
    }
}
