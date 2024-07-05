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

package com.linecorp.armeria.common;

import static com.linecorp.armeria.internal.common.TlsProviderUtil.normalizeHostname;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

final class MappedTlsProvider implements TlsProvider {

    private final Map<String, TlsKeyPair> tlsKeyPairs;
    private final boolean allowsUnsafeCiphers;
    private final Consumer<SslContextBuilder> tlsCustomizer;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    MappedTlsProvider(Map<String, TlsKeyPair> tlsKeyPairs, Consumer<SslContextBuilder> tlsCustomizer,
                      boolean allowsUnsafeCiphers, @Nullable MeterIdPrefix meterIdPrefix) {
        this.tlsKeyPairs = tlsKeyPairs;
        this.allowsUnsafeCiphers = allowsUnsafeCiphers;
        this.tlsCustomizer = tlsCustomizer;
        this.meterIdPrefix = meterIdPrefix;
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
    public TlsKeyPair find(String hostname) {
        requireNonNull(hostname, "hostname");
        if ("*".equals(hostname)) {
            return tlsKeyPairs.get("*");
        }
        hostname = normalizeHostname(hostname);

        TlsKeyPair tlsKeyPair = tlsKeyPairs.get(hostname);
        if (tlsKeyPair != null) {
            return tlsKeyPair;
        }

        // No exact match, let's try a wildcard match.
        final int idx = hostname.indexOf('.');
        if (idx != -1) {
            tlsKeyPair = tlsKeyPairs.get(hostname.substring(idx));
            if (tlsKeyPair != null) {
                return tlsKeyPair;
            }
        }
        // Try to find the default TlsKeyPair.
        return tlsKeyPairs.get("*");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MappedTlsProvider)) {
            return false;
        }
        final MappedTlsProvider that = (MappedTlsProvider) o;
        return allowsUnsafeCiphers == that.allowsUnsafeCiphers &&
               tlsKeyPairs.equals(that.tlsKeyPairs) &&
               tlsCustomizer.equals(that.tlsCustomizer) &&
               Objects.equals(meterIdPrefix, that.meterIdPrefix);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tlsKeyPairs, allowsUnsafeCiphers, tlsCustomizer, meterIdPrefix);
    }
}