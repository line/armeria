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

import java.util.function.Consumer;

import com.linecorp.armeria.common.AbstractTlsProviderBuilder;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

public final class ServerTlsProviderBuilder extends AbstractTlsProviderBuilder {

    // We may add more methods to this class to provide server-specific features in the future.

    @Override
    public TlsProvider build() {
        return super.build();
    }

    // Override the return type of the chaining methods in the superclass.

    @Override
    public ServerTlsProviderBuilder set(String hostname, TlsKeyPair tlsKeyPair) {
        return (ServerTlsProviderBuilder) super.set(hostname, tlsKeyPair);
    }

    @Override
    public ServerTlsProviderBuilder setDefault(TlsKeyPair tlsKeyPair) {
        return (ServerTlsProviderBuilder) super.setDefault(tlsKeyPair);
    }

    @Override
    public ServerTlsProviderBuilder allowsUnsafeCiphers(boolean allowsUnsafeCiphers) {
        return (ServerTlsProviderBuilder) super.allowsUnsafeCiphers(allowsUnsafeCiphers);
    }

    @Override
    public ServerTlsProviderBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        return (ServerTlsProviderBuilder) super.tlsCustomizer(tlsCustomizer);
    }

    @Override
    public ServerTlsProviderBuilder meterIdPrefix(MeterIdPrefix meterIdPrefix) {
        return (ServerTlsProviderBuilder) super.meterIdPrefix(meterIdPrefix);
    }
}
