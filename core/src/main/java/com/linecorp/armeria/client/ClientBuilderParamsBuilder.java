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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.internal.client.ClientBuilderParamsUtil.nullOrEmptyToSlash;

import java.net.URI;
import java.net.URISyntaxException;

import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Allows creation of a new {@link ClientBuilderParams} based on a previous {@link ClientBuilderParams}.
 */
public final class ClientBuilderParamsBuilder {

    private final ClientBuilderParams params;

    @Nullable
    private SerializationFormat serializationFormat;
    @Nullable
    private String absolutePathRef;
    @Nullable
    private Class<?> type;
    @Nullable
    private ClientOptions options;

    ClientBuilderParamsBuilder(ClientBuilderParams params) {
        this.params = params;
    }

    /**
     * Sets the {@link SerializationFormat} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder serializationFormat(SerializationFormat serializationFormat) {
        this.serializationFormat = serializationFormat;
        return this;
    }

    /**
     * Sets the {@param absolutePathRef} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder absolutePathRef(String absolutePathRef) {
        this.absolutePathRef = absolutePathRef;
        return this;
    }

    /**
     * Sets the {@param type} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder clientType(Class<?> type) {
        this.type = type;
        return this;
    }

    /**
     * Sets the {@link ClientOptions} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder options(ClientOptions options) {
        this.options = options;
        return this;
    }

    /**
     * Builds a new {@link ClientBuilderParams} based on the configured properties.
     */
    public ClientBuilderParams build() {
        final Scheme scheme;
        if (serializationFormat != null) {
            scheme = Scheme.of(serializationFormat, params.scheme().sessionProtocol());
        } else {
            scheme = params.scheme();
        }
        final String schemeStr;
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            schemeStr = scheme.sessionProtocol().uriText();
        } else {
            schemeStr = scheme.uriText();
        }

        final String path;
        if (absolutePathRef != null) {
            path = nullOrEmptyToSlash(absolutePathRef);
        } else {
            path = params.absolutePathRef();
        }

        final URI prevUri = params.uri();
        final URI uri;
        try {
            uri = new URI(schemeStr, prevUri.getRawAuthority(), path,
                          prevUri.getRawQuery(), prevUri.getRawFragment());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return new DefaultClientBuilderParams(scheme, params.endpointGroup(), uri.getRawPath(),
                                              uri, firstNonNull(type, params.clientType()),
                                              firstNonNull(options, params.options()));
    }
}
