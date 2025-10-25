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

import static com.linecorp.armeria.internal.client.ClientBuilderParamsUtil.nullOrEmptyToSlash;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.internal.client.ClientBuilderParamsUtil;
import com.linecorp.armeria.internal.client.endpoint.UndefinedEndpointGroup;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Allows creation of a new {@link ClientBuilderParams} based on a previous {@link ClientBuilderParams}.
 */
@UnstableApi
public final class ClientBuilderParamsBuilder {

    private final URI uri;
    private final EndpointGroup endpointGroup;
    private final SessionProtocol sessionProtocol;

    private SerializationFormat serializationFormat;
    private String absolutePathRef;

    @Nullable
    private Class<?> type;
    @Nullable
    private ClientOptions options;

    ClientBuilderParamsBuilder(ClientBuilderParams params) {
        uri = params.uri();
        endpointGroup = params.endpointGroup();
        sessionProtocol = params.scheme().sessionProtocol();

        serializationFormat = params.scheme().serializationFormat();
        absolutePathRef = params.absolutePathRef();
        type = params.clientType();
        options = params.options();
    }

    ClientBuilderParamsBuilder(URI uri) {
        this.uri = uri;
        final Scheme scheme = Scheme.parse(uri.getScheme());
        final EndpointGroup endpointGroup;
        if (ClientBuilderParamsUtil.isInternalUri(uri)) {
            endpointGroup = UndefinedEndpointGroup.of();
        } else {
            endpointGroup = Endpoint.parse(uri.getRawAuthority());
        }
        final String absolutePathRef;
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append(nullOrEmptyToSlash(uri.getRawPath()));
            if (uri.getRawQuery() != null) {
                buf.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                buf.append('#').append(uri.getRawFragment());
            }
            absolutePathRef = buf.toString();
        }
        this.endpointGroup = endpointGroup;
        serializationFormat = scheme.serializationFormat();
        sessionProtocol = scheme.sessionProtocol();
        this.absolutePathRef = absolutePathRef;
    }

    ClientBuilderParamsBuilder(Scheme scheme, EndpointGroup endpointGroup, @Nullable String absolutePathRef) {
        this.endpointGroup = endpointGroup;
        final String schemeStr = scheme.shortUriText();
        final String normalizedAbsolutePathRef = nullOrEmptyToSlash(absolutePathRef);
        final URI uri;
        if (endpointGroup instanceof Endpoint) {
            uri = URI.create(schemeStr + "://" + ((Endpoint) endpointGroup).authority() +
                             normalizedAbsolutePathRef);
        } else {
            // Create a valid URI which will never succeed.
            uri = URI.create(schemeStr + "://" + ClientBuilderParamsUtil.ENDPOINT_GROUP_PREFIX +
                             Integer.toHexString(System.identityHashCode(endpointGroup)) +
                             ":1" + normalizedAbsolutePathRef);
        }
        this.uri = uri;
        serializationFormat = scheme.serializationFormat();
        sessionProtocol = scheme.sessionProtocol();
        this.absolutePathRef = normalizedAbsolutePathRef;
    }

    /**
     * Sets the {@link SerializationFormat} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder serializationFormat(SerializationFormat serializationFormat) {
        this.serializationFormat = requireNonNull(serializationFormat, "serializationFormat");
        return this;
    }

    /**
     * Sets the {@param absolutePathRef} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder absolutePathRef(String absolutePathRef) {
        this.absolutePathRef = requireNonNull(absolutePathRef, "absolutePathRef");
        return this;
    }

    /**
     * Sets the {@param type} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder clientType(Class<?> type) {
        this.type = requireNonNull(type, "type");
        return this;
    }

    /**
     * Sets the {@link ClientOptions} for this {@link ClientBuilderParams}.
     */
    public ClientBuilderParamsBuilder options(ClientOptions options) {
        this.options = requireNonNull(options, "options");
        return this;
    }

    /**
     * Builds a new {@link ClientBuilderParams} based on the configured properties.
     */
    public ClientBuilderParams build() {
        final ClientOptions options = requireNonNull(this.options, "options");
        final Class<?> type = requireNonNull(this.type, "type");

        final SerializationFormat serializationFormat = this.serializationFormat;
        final String absolutePathRef = this.absolutePathRef;
        final ClientFactory factory = options.factory();
        final Scheme scheme = factory.validateScheme(Scheme.of(serializationFormat, sessionProtocol));
        final String schemeStr = scheme.shortUriText();

        final String path = nullOrEmptyToSlash(absolutePathRef);

        final URI uri;
        try {
            uri = new URI(schemeStr + "://" + this.uri.getRawAuthority() + path);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e);
        }
        return new DefaultClientBuilderParams(scheme, endpointGroup, path,
                                              factory.validateUri(uri), type, options);
    }
}
