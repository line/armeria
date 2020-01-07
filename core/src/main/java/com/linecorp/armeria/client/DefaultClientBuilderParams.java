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

import javax.annotation.Nullable;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.internal.TemporaryThreadLocals;

/**
 * Default {@link ClientBuilderParams} implementation.
 *
 * @deprecated Use {@link ClientBuilderParams#of(ClientFactory, URI, Class, ClientOptions)}.
 */
@Deprecated
public class DefaultClientBuilderParams implements ClientBuilderParams {

    private final ClientFactory factory;
    private final Scheme scheme;
    private final EndpointGroup endpointGroup;
    private final String absolutePathRef;
    private final URI uri;
    private final Class<?> type;
    private final ClientOptions options;

    /**
     * Creates a new instance.
     *
     * @deprecated Use {@link ClientBuilderParams#of(ClientFactory, URI, Class, ClientOptions)}.
     */
    @Deprecated
    public DefaultClientBuilderParams(ClientFactory factory, URI uri, Class<?> type,
                                      ClientOptions options) {
        this.factory = requireNonNull(factory, "factory");
        this.uri = factory.validateUri(uri);
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");

        scheme = factory.validateScheme(Scheme.parse(uri.getScheme()));
        endpointGroup = Endpoint.parse(uri.getRawAuthority());

        final StringBuilder buf = TemporaryThreadLocals.get().stringBuilder();
        buf.append(nullOrEmptyToSlash(uri.getRawPath()));
        if (uri.getRawQuery() != null) {
            buf.append('?').append(uri.getRawQuery());
        }
        if (uri.getRawFragment() != null) {
            buf.append('#').append(uri.getRawFragment());
        }
        absolutePathRef = buf.toString();
    }

    DefaultClientBuilderParams(ClientFactory factory, Scheme scheme, EndpointGroup endpointGroup,
                               @Nullable String absolutePathRef,
                               Class<?> type, ClientOptions options) {
        this.factory = requireNonNull(factory, "factory");
        this.scheme = factory.validateScheme(scheme);
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");

        final String schemeStr;
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            schemeStr = scheme.sessionProtocol().uriText();
        } else {
            schemeStr = scheme.uriText();
        }

        final String normalizedAbsolutePathRef = nullOrEmptyToSlash(absolutePathRef);
        final URI uri;
        if (endpointGroup instanceof Endpoint) {
            uri = URI.create(schemeStr + "://" + ((Endpoint) endpointGroup).authority() +
                             normalizedAbsolutePathRef);
        } else {
            // Create a valid URI which will never succeed.
            uri = URI.create(schemeStr + "://group-" +
                             Integer.toHexString(System.identityHashCode(endpointGroup)) +
                             ":0" + normalizedAbsolutePathRef);
        }

        this.uri = factory.validateUri(uri);
        this.absolutePathRef = normalizedAbsolutePathRef;
    }

    private static String nullOrEmptyToSlash(@Nullable String absolutePathRef) {
        return Strings.isNullOrEmpty(absolutePathRef) ? "/" : absolutePathRef;
    }

    @Override
    public ClientFactory factory() {
        return factory;
    }

    @Override
    public Scheme scheme() {
        return scheme;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
    }

    @Override
    public String absolutePathRef() {
        return absolutePathRef;
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
                          .add("scheme", scheme)
                          .add("endpoint", endpointGroup)
                          .add("absolutePathRef", absolutePathRef)
                          .add("type", type)
                          .add("options", options).toString();
    }
}
