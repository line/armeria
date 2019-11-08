/*
 * Copyright 2016 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.google.common.base.MoreObjects;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
public class DefaultClientBuilderParams implements ClientBuilderParams {

    private final ClientFactory factory;
    private final URI uri;
    private final Class<?> type;
    private final ClientOptions options;

    /**
     * Creates a new instance.
     */
    public DefaultClientBuilderParams(ClientFactory factory, URI uri, Class<?> type,
                                      ClientOptions options) {
        this.factory = requireNonNull(factory, "factory");
        this.uri = requireNonNull(uri, "uri");
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");
    }

    @Override
    public ClientFactory factory() {
        return factory;
    }

    @Override
    public URI uri() {
        return uri;
    }

    @Override
    public Class<?> clientType() {
        return type;
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("factory", factory)
                          .add("uri", uri)
                          .add("type", type)
                          .add("options", options).toString();
    }
}
