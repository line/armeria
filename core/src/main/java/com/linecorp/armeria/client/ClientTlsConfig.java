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

import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AbstractTlsConfig;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Provides client-side TLS configuration for {@link TlsProvider}.
 */
@UnstableApi
public final class ClientTlsConfig extends AbstractTlsConfig {

    static final ClientTlsConfig NOOP = builder().build();

    /**
     * Returns a new {@link ClientTlsConfigBuilder}.
     */
    public static ClientTlsConfigBuilder builder() {
        return new ClientTlsConfigBuilder();
    }

    private final boolean tlsNoVerifySet;
    private final Set<String> insecureHosts;

    ClientTlsConfig(boolean allowsUnsafeCiphers, @Nullable MeterIdPrefix meterIdPrefix,
                    Consumer<SslContextBuilder> tlsCustomizer, boolean tlsNoVerifySet,
                    Set<String> insecureHosts) {
        super(allowsUnsafeCiphers, meterIdPrefix, tlsCustomizer);
        this.tlsNoVerifySet = tlsNoVerifySet;
        this.insecureHosts = insecureHosts;
    }

    /**
     * Returns whether the verification of server's TLS certificate chain is disabled.
     */
    public boolean tlsNoVerifySet() {
        return tlsNoVerifySet;
    }

    /**
     * Returns the hosts for which the verification of server's TLS certificate chain is disabled.
     */
    public Set<String> insecureHosts() {
        return insecureHosts;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ClientTlsConfig)) {
            return false;
        }
        if (!super.equals(o)) {
            return false;
        }

        final ClientTlsConfig that = (ClientTlsConfig) o;
        return tlsNoVerifySet == that.tlsNoVerifySet && insecureHosts.equals(that.insecureHosts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode(), tlsNoVerifySet, insecureHosts);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("allowsUnsafeCiphers", allowsUnsafeCiphers())
                          .add("meterIdPrefix", meterIdPrefix())
                          .add("tlsCustomizer", tlsCustomizer())
                          .add("tlsNoVerifySet", tlsNoVerifySet)
                          .add("insecureHosts", insecureHosts)
                          .toString();
    }
}
