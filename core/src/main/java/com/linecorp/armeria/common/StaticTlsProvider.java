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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.Objects;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

final class StaticTlsProvider implements TlsProvider {

    private final TlsKeyPair tlsKeyPair;
    private final boolean allowsUnsafeCiphers;
    private final Consumer<SslContextBuilder> tlsCustomizer;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    StaticTlsProvider(TlsKeyPair tlsKeyPair) {
        this(tlsKeyPair, b -> {}, false, null);
    }

    StaticTlsProvider(TlsKeyPair tlsKeyPair, Consumer<SslContextBuilder> tlsCustomizer,
                      boolean allowsUnsafeCiphers,
                      @Nullable MeterIdPrefix meterIdPrefix) {
        requireNonNull(tlsKeyPair, "tlsKeyPair");
        requireNonNull(tlsCustomizer, "tlsCustomizer");
        this.tlsKeyPair = tlsKeyPair;
        this.allowsUnsafeCiphers = allowsUnsafeCiphers;
        this.tlsCustomizer = tlsCustomizer;
        this.meterIdPrefix = meterIdPrefix;
    }

    @Override
    public TlsKeyPair find(String hostname) {
        return tlsKeyPair;
    }

    @Override
    public boolean allowsUnsafeCiphers() {
        return allowsUnsafeCiphers;
    }

    @Override
    public Consumer<SslContextBuilder> tlsCustomizer() {
        return tlsCustomizer;
    }

    @Override
    public MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof StaticTlsProvider)) {
            return false;
        }
        final StaticTlsProvider that = (StaticTlsProvider) o;
        return allowsUnsafeCiphers == that.allowsUnsafeCiphers &&
               tlsKeyPair.equals(that.tlsKeyPair) &&
               tlsCustomizer.equals(that.tlsCustomizer) &&
               Objects.equals(meterIdPrefix, that.meterIdPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tlsKeyPair, allowsUnsafeCiphers, tlsCustomizer, meterIdPrefix);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("tlsKeyPair", tlsKeyPair)
                          .add("allowsUnsafeCiphers", allowsUnsafeCiphers)
                          .add("tlsCustomizer", tlsCustomizer)
                          .add("meterIdPrefix", meterIdPrefix)
                          .toString();
    }
}
