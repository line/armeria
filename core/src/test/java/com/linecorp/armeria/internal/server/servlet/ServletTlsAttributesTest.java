/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.internal.server.servlet;

import static com.linecorp.armeria.internal.server.servlet.ServletTlsAttributes.guessKeySize;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ServletTlsAttributesTest {
    @Test
    void testGuessBadKeySize() {
        assertThat(guessKeySize(null)).isZero();
        assertThat(guessKeySize("")).isZero();
        assertThat(guessKeySize("_")).isZero();
        assertThat(guessKeySize("_FOO")).isZero();
        assertThat(guessKeySize("_WITH")).isZero();
        assertThat(guessKeySize("_WITH_BAR")).isZero();
        assertThat(guessKeySize("TLS_")).isZero();
        assertThat(guessKeySize("TLS_FOO")).isZero();
        assertThat(guessKeySize("TLS_FOO_WITH_")).isZero();
        assertThat(guessKeySize("TLS_FOO_WITH_BAR")).isZero();
    }

    @Test
    void testGuessGoodKeySize() {
        assertThat(guessKeySize("TLS_AES_256_GCM_SHA384")).isEqualTo(256);
        assertThat(guessKeySize("TLS_AES_128_GCM_SHA256")).isEqualTo(128);
        assertThat(guessKeySize("TLS_CHACHA20_POLY1305_SHA256")).isEqualTo(256);
        assertThat(guessKeySize("TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384")).isEqualTo(256);
        assertThat(guessKeySize("TLS_DH_anon_WITH_AES_128_GCM_SHA256")).isEqualTo(128);
        assertThat(guessKeySize("TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256")).isEqualTo(256);
        assertThat(guessKeySize("TLS_DHE_RSA_WITH_ARIA256_GCM_SHA384")).isEqualTo(256);
        assertThat(guessKeySize("TLS_DHE_RSA_WITH_ARIA128_GCM_SHA256")).isEqualTo(128);
        assertThat(guessKeySize("TLS_ECDHE_RSA_WITH_CAMELLIA256_SHA384")).isEqualTo(256);
        assertThat(guessKeySize("TLS_DHE_RSA_WITH_CAMELLIA128_SHA256")).isEqualTo(128);
        assertThat(guessKeySize("TLS_RSA_WITH_IDEA_CBC_SHA")).isEqualTo(128);
        assertThat(guessKeySize("TLS_RSA_WITH_SEED_SHA")).isEqualTo(128);
    }
}
