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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.SslContextFactory;

class TlsProviderMappingTest {

    private static final SslContextFactory factory = new SslContextFactory(Flags.meterRegistry());

    @Test
    void testNoDefault() {
        final TlsProvider tlsProvider = TlsProvider.builder()
                                                   .keyPair("example.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("api.example.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("foo.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("*.foo.com", TlsKeyPair.ofSelfSigned())
                                                   .build();
        final TlsProviderMapping mapping = new TlsProviderMapping(tlsProvider,
                                                                  TlsEngineType.OPENSSL,
                                                                  ServerTlsConfig.builder().build(),
                                                                  factory);
        assertThat(mapping.map("example.com")).isNotNull();
        assertThat(mapping.map("api.example.com")).isNotNull();
        assertThatThrownBy(() -> mapping.map("web.example.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No TLS key pair found for web.example.com");
        assertThat(mapping.map("foo.com")).isNotNull();
        assertThat(mapping.map("bar.foo.com")).isNotNull();
        assertThatThrownBy(() -> mapping.map("baz.bar.foo.com"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No TLS key pair found for baz.bar.foo.com");
    }

    @Test
    void testWithDefault() {
        final TlsProvider tlsProvider = TlsProvider.builder()
                                                   .keyPair(TlsKeyPair.ofSelfSigned())
                                                   .keyPair("example.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("api.example.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("foo.com", TlsKeyPair.ofSelfSigned())
                                                   .keyPair("*.foo.com", TlsKeyPair.ofSelfSigned())
                                                   .build();
        final TlsProviderMapping mapping = new TlsProviderMapping(tlsProvider,
                                                                  TlsEngineType.OPENSSL,
                                                                  ServerTlsConfig.builder().build(),
                                                                  factory);
        assertThat(mapping.map("example.com")).isNotNull();
        assertThat(mapping.map("api.example.com")).isNotNull();
        assertThat(mapping.map("web.example.com")).isNotNull();
        assertThat(mapping.map("foo.com")).isNotNull();
        assertThat(mapping.map("bar.foo.com")).isNotNull();
        assertThat(mapping.map("baz.bar.foo.com")).isNotNull();
    }
}
