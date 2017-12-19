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

/**
 * Creates a new {@link ClientOptions} using the builder pattern.
 *
 * @see ClientBuilder
 */
public final class ClientOptionsBuilder extends AbstractClientOptionsBuilder<ClientOptionsBuilder> {

    /**
     * Creates a new instance with the default options.
     */
    public ClientOptionsBuilder() {}

    /**
     * Creates a new instance with the specified base options.
     */
    public ClientOptionsBuilder(ClientOptions options) {
        super(options);
    }

    /**
     * Returns a newly-created {@link ClientOptions} based on the {@link ClientOptionValue}s of this builder.
     */
    public ClientOptions build() {
        return buildOptions();
    }
}
