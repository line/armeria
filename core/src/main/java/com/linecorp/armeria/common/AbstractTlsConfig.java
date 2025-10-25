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

import java.util.Objects;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * Provides common configuration for TLS.
 */
@UnstableApi
public abstract class AbstractTlsConfig {

    private final boolean allowsUnsafeCiphers;

    @Nullable
    private final MeterIdPrefix meterIdPrefix;

    private final Consumer<SslContextBuilder> tlsCustomizer;

    /**
     * Creates a new instance.
     */
    protected AbstractTlsConfig(boolean allowsUnsafeCiphers, @Nullable MeterIdPrefix meterIdPrefix,
                                Consumer<SslContextBuilder> tlsCustomizer) {
        this.allowsUnsafeCiphers = allowsUnsafeCiphers;
        this.meterIdPrefix = meterIdPrefix;
        this.tlsCustomizer = tlsCustomizer;
    }

    /**
     * Returns whether to allow the bad cipher suites listed in
     * <a href="https://datatracker.ietf.org/doc/html/rfc7540#appendix-A">RFC7540</a> for TLS handshake.
     */
    public final boolean allowsUnsafeCiphers() {
        return allowsUnsafeCiphers;
    }

    /**
     * Sets the {@link MeterIdPrefix} for the TLS metrics.
     */
    @Nullable
    public final MeterIdPrefix meterIdPrefix() {
        return meterIdPrefix;
    }

    /**
     * Returns the {@link Consumer} which can arbitrarily configure the {@link SslContextBuilder}.
     */
    public final Consumer<SslContextBuilder> tlsCustomizer() {
        return tlsCustomizer;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbstractTlsConfig)) {
            return false;
        }
        final AbstractTlsConfig that = (AbstractTlsConfig) o;
        return allowsUnsafeCiphers == that.allowsUnsafeCiphers &&
               Objects.equals(meterIdPrefix, that.meterIdPrefix) &&
               tlsCustomizer.equals(that.tlsCustomizer);
    }

    @Override
    public int hashCode() {
        return Objects.hash(allowsUnsafeCiphers, meterIdPrefix, tlsCustomizer);
    }
}
