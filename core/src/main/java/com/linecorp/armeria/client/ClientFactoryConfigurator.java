/*
 *  Copyright 2026 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */
package com.linecorp.armeria.client;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Configures a built-in default {@link ClientFactory} using the specified {@link ClientFactoryBuilder}.
 *
 * <p>This configurator is invoked while creating the built-in default {@link ClientFactory}s returned by
 * {@link ClientFactory#ofDefault()} and {@link ClientFactory#insecure()}.
 *
 * <p>This configurator is applied to both the default and insecure built-in
 * {@link ClientFactory}s, so it must not call
 * {@link ClientFactory#ofDefault()} or {@link ClientFactory#insecure()}.
 *
 * <p>Because {@link ClientFactory#insecure()} applies {@link ClientFactoryBuilder#tlsNoVerify()} after this
 * configurator runs, TLS verification-related customization is unsupported.
 */
@UnstableApi
@FunctionalInterface
public interface ClientFactoryConfigurator {

    /**
     * Configures the built-in default {@link ClientFactory} using the specified
     * {@link ClientFactoryBuilder}.
     *
     * <p>Note that {@link ClientFactoryBuilder#tlsNoVerify()} is applied after this method returns when
     * creating {@link ClientFactory#insecure()}.
     */
    void configure(ClientFactoryBuilder builder);

    /**
     * Returns a {@link ClientFactoryConfigurator} that does not customize the specified
     * {@link ClientFactoryBuilder}.
     */
    static ClientFactoryConfigurator noop() {
        return builder -> {
        };
    }
}
