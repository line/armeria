/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.server.saml;

import static java.util.Objects.requireNonNull;

/**
 * A builder which builds a {@link SamlAssertionConsumerConfig}.
 */
public final class SamlAssertionConsumerConfigBuilder {

    private final SamlServiceProviderBuilder parent;

    private SamlEndpoint endpoint;
    private boolean isDefault;

    SamlAssertionConsumerConfigBuilder(SamlServiceProviderBuilder parent) {
        this.parent = parent;
    }

    /**
     * Returns a {@link SamlEndpoint} of this assertion consumer service.
     */
    SamlEndpoint endpoint() {
        return endpoint;
    }

    /**
     * Sets an endpoint of this assertion consumer service.
     */
    public SamlAssertionConsumerConfigBuilder endpoint(SamlEndpoint endpoint) {
        this.endpoint = requireNonNull(endpoint, "endpoint");
        return this;
    }

    /**
     * Sets this assertion consumer service as a default.
     */
    public SamlAssertionConsumerConfigBuilder asDefault() {
        isDefault = true;
        return this;
    }

    /**
     * Returns a {@link SamlServiceProvider} which is the parent of this builder.
     */
    public SamlServiceProviderBuilder and() {
        return parent;
    }

    /**
     * Builds a {@link SamlAssertionConsumerConfig}.
     */
    SamlAssertionConsumerConfig build() {
        return new SamlAssertionConsumerConfig(endpoint, isDefault);
    }
}
