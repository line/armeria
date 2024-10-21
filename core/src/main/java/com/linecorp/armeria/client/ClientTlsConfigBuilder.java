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

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AbstractTlsConfigBuilder;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

/**
 * A builder class for creating a {@link ClientTlsConfig}.
 */
@UnstableApi
public final class ClientTlsConfigBuilder extends AbstractTlsConfigBuilder<ClientTlsConfigBuilder> {

    private boolean tlsNoVerifySet;
    private final Set<String> insecureHosts = new HashSet<>();

    ClientTlsConfigBuilder() {}

    /**
     * Disables the verification of server's TLS certificate chain. If you want to disable verification for
     * only specific hosts, use {@link #tlsNoVerifyHosts(String...)}.
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see InsecureTrustManagerFactory
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsConfigBuilder tlsNoVerify() {
        tlsNoVerifySet = true;
        checkState(insecureHosts.isEmpty(), "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        return this;
    }

    /**
     * Disables the verification of server's TLS certificate chain for specific hosts. If you want to disable
     * all verification, use {@link #tlsNoVerify()} .
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsConfigBuilder tlsNoVerifyHosts(String... insecureHosts) {
        requireNonNull(insecureHosts, "insecureHosts");
        return tlsNoVerifyHosts(ImmutableList.copyOf(insecureHosts));
    }

    /**
     * Disables the verification of server's TLS certificate chain for specific hosts. If you want to disable
     * all verification, use {@link #tlsNoVerify()} .
     *
     * <p><strong>Note:</strong> You should never use this in production but only for a testing purpose.
     *
     * @see #tlsCustomizer(Consumer)
     */
    public ClientTlsConfigBuilder tlsNoVerifyHosts(Iterable<String> insecureHosts) {
        requireNonNull(insecureHosts, "insecureHosts");
        checkState(!tlsNoVerifySet, "tlsNoVerify() and tlsNoVerifyHosts() are mutually exclusive.");
        insecureHosts.forEach(this.insecureHosts::add);
        return this;
    }

    /**
     * Returns a newly-created {@link ClientTlsConfig} based on the properties of this builder.
     */
    public ClientTlsConfig build() {
        return new ClientTlsConfig(allowsUnsafeCiphers(), meterIdPrefix(), tlsCustomizer(),
                                   tlsNoVerifySet, ImmutableSet.copyOf(insecureHosts));
    }
}
