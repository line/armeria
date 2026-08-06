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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;

class ClientTlsProviderBuilderTest {

    @Test
    void testBuild() {
        assertThatThrownBy(() -> {
            TlsProvider.builder()
                       .build();
        }).isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("No TLS key pair is set.");
    }

    @Test
    void testMapping() {
        final TlsKeyPair exactKeyPair = TlsKeyPair.ofSelfSigned();
        final TlsKeyPair wildcardKeyPair = TlsKeyPair.ofSelfSigned();
        final TlsKeyPair defaultKeyPair = TlsKeyPair.ofSelfSigned();
        final TlsKeyPair barKeyPair = TlsKeyPair.ofSelfSigned();
        final TlsKeyPair barWildKeyPair = TlsKeyPair.ofSelfSigned();
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair(defaultKeyPair)
                           .keyPair("example.com", exactKeyPair)
                           .keyPair("*.foo.com", wildcardKeyPair)
                           .keyPair("*.bar.com", barWildKeyPair)
                           .keyPair("bar.com", barKeyPair)
                           .build();
        assertThat(tlsProvider.keyPair("any.com")).isEqualTo(defaultKeyPair);
        // Exact match
        assertThat(tlsProvider.keyPair("example.com")).isEqualTo(exactKeyPair);
        // Wildcard match
        assertThat(tlsProvider.keyPair("bar.foo.com")).isEqualTo(wildcardKeyPair);

        // Not a wildcard match
        assertThat(tlsProvider.keyPair("foo.com")).isEqualTo(defaultKeyPair);
        // No nested wildcard support
        assertThat(tlsProvider.keyPair("baz.bar.foo.com")).isEqualTo(defaultKeyPair);

        assertThat(tlsProvider.keyPair("bar.com")).isEqualTo(barKeyPair);
        assertThat(tlsProvider.keyPair("foo.bar.com")).isEqualTo(barWildKeyPair);
        assertThat(tlsProvider.keyPair("foo.foo.bar.com")).isEqualTo(defaultKeyPair);
    }
}
