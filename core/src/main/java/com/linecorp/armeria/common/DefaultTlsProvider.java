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

import static io.netty.util.internal.StringUtil.commonSuffixOfLength;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.function.Consumer;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

final class DefaultTlsProvider implements TlsProvider {

    private final Map<String, TlsKeyPair> tlsKeyPairs;
    private final boolean allowsUnsafeCiphers;
    private final Consumer<SslContextBuilder> tlsCustomizer;
    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    DefaultTlsProvider(Map<String, TlsKeyPair> tlsKeyPairs, Consumer<SslContextBuilder> tlsCustomizer,
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

        for (Entry<String, TlsKeyPair> entry : tlsKeyPairs.entrySet()) {
            if (matches(entry.getKey(), hostname)) {
                return entry.getValue();
            }
        }
        // Try to find the default TlsKeyPair.
        return tlsKeyPairs.get("*");
    }

    // Forked from https://github.com/netty/netty/blob/60430c80e7f8718ecd07ac31e01297b42a176b87/common/src/main/java/io/netty/util/DomainNameMapping.java

    /**
     * Simple function to match <a href="https://en.wikipedia.org/wiki/Wildcard_DNS_record">DNS wildcard</a>.
     */
    private static boolean matches(String template, String hostName) {
        if (template.startsWith("*.")) {
            return template.regionMatches(2, hostName, 0, hostName.length()) ||
                   commonSuffixOfLength(hostName, template, template.length() - 1);
        }
        return template.equals(hostName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultTlsProvider)) {
            return false;
        }
        final DefaultTlsProvider that = (DefaultTlsProvider) o;
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
