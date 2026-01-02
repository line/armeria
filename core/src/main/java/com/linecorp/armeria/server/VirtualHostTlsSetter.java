/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.Preconditions.checkState;

import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.server.ServerTlsSpec.ServerTlsSpecBuilder;

import io.netty.handler.ssl.SslContextBuilder;

final class VirtualHostTlsSetter {

    @Nullable
    private TlsKeyPair tlsKeyPair;
    @Nullable
    private Consumer<? super SslContextBuilder> tlsCustomizer;
    @Nullable
    private KeyManagerFactory keyManagerFactory;
    @Nullable
    private Boolean tlsSelfSigned;

    private VirtualHostTlsSetter() {}

    private VirtualHostTlsSetter(@Nullable TlsKeyPair tlsKeyPair,
                                 @Nullable Consumer<? super SslContextBuilder> tlsCustomizer,
                                 @Nullable KeyManagerFactory keyManagerFactory,
                                 @Nullable Boolean tlsSelfSigned) {
        this.tlsKeyPair = tlsKeyPair;
        this.tlsCustomizer = tlsCustomizer;
        this.keyManagerFactory = keyManagerFactory;
        this.tlsSelfSigned = tlsSelfSigned;
    }

    static VirtualHostTlsSetter newInstance() {
        return new VirtualHostTlsSetter();
    }

    VirtualHostTlsSetter tlsKeyPair(TlsKeyPair tlsKeyPair) {
        checkState(keyManagerFactory == null && this.tlsKeyPair == null, "tls() has already been set");
        this.tlsKeyPair = tlsKeyPair;
        return this;
    }

    VirtualHostTlsSetter tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
        this.tlsCustomizer = mergeTlsCustomizers(this.tlsCustomizer, tlsCustomizer);
        return this;
    }

    VirtualHostTlsSetter keyManagerFactory(KeyManagerFactory keyManagerFactory) {
        checkState(this.keyManagerFactory == null && tlsKeyPair == null, "tls() has already been set");
        this.keyManagerFactory = keyManagerFactory;
        return this;
    }

    VirtualHostTlsSetter tlsSelfSigned(boolean tlsSelfSigned) {
        this.tlsSelfSigned = tlsSelfSigned;
        return this;
    }

    boolean isKeyPairSet() {
        return tlsKeyPair != null || keyManagerFactory != null;
    }

    boolean keySpecified() {
        return tlsKeyPair != null || keyManagerFactory != null || Boolean.TRUE.equals(tlsSelfSigned);
    }

    boolean shouldTlsSelfSign() {
        return Boolean.TRUE.equals(tlsSelfSigned);
    }

    void validate() {
        if (!keySpecified() && tlsCustomizer != null) {
            throw new IllegalStateException("Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");
        }
    }

    VirtualHostTlsSetter shallowCopy() {
        return new VirtualHostTlsSetter(tlsKeyPair, tlsCustomizer, keyManagerFactory, tlsSelfSigned);
    }

    ServerTlsSpec toServerTlsSpec(TlsEngineType tlsEngineType, String hostnamePattern) {
        if (tlsKeyPair == null && keyManagerFactory == null) {
            throw new IllegalStateException("Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");
        }
        assert keyManagerFactory == null || tlsKeyPair == null;
        final ServerTlsSpecBuilder tlsSpecBuilder = ServerTlsSpec.builder()
                                                                 .engineType(tlsEngineType);
        if (tlsKeyPair != null) {
            tlsSpecBuilder.tlsKeyPair(tlsKeyPair);
        } else if (keyManagerFactory != null) {
            tlsSpecBuilder.keyManagerFactory(keyManagerFactory);
        }
        if (tlsCustomizer != null) {
            tlsSpecBuilder.tlsCustomizer(tlsCustomizer);
        }
        tlsSpecBuilder.hostnamePattern(hostnamePattern);
        return tlsSpecBuilder.build();
    }

    @SuppressWarnings("unchecked")
    private static Consumer<? super SslContextBuilder> mergeTlsCustomizers(
            @Nullable Consumer<? super SslContextBuilder> first,
            Consumer<? super SslContextBuilder> second) {
        final Consumer<SslContextBuilder> first0 = (Consumer<SslContextBuilder>) first;
        final Consumer<SslContextBuilder> second0 = (Consumer<SslContextBuilder>) second;
        if (first0 == null) {
            return second0;
        }
        return first0.andThen(second0);
    }
}
