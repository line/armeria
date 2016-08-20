/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.Optional;
import java.util.Set;

import com.linecorp.armeria.common.Scheme;

/**
 * A skeletal {@link ClientFactory} implementation.
 */
public abstract class AbstractClientFactory implements ClientFactory {

    @Override
    public final <T> T newClient(String uri, Class<T> clientType, ClientOptionValue<?>... options) {
        requireNonNull(uri, "uri");
        requireNonNull(options, "options");
        return newClient(URI.create(uri), clientType, ClientOptions.of(options));
    }

    @Override
    public final <T> T newClient(String uri, Class<T> clientType, ClientOptions options) {
        requireNonNull(uri, "uri");
        return newClient(URI.create(uri), clientType, options);
    }

    @Override
    public final <T> T newClient(URI uri, Class<T> clientType, ClientOptionValue<?>... options) {
        requireNonNull(options, "options");
        return newClient(uri, clientType, ClientOptions.of(options));
    }

    /**
     * Makes sure the scheme of the specified {@link URI} is supported by this {@link ClientFactory}.
     *
     * @param uri the {@link URI} of the server endpoint
     * @return the parsed {@link Scheme}
     *
     * @throws IllegalArgumentException if the scheme of the specified {@link URI} is not supported by this
     *                                  {@link ClientFactory}
     */
    protected final Scheme validateScheme(URI uri) {
        requireNonNull(uri, "uri");

        final String scheme = uri.getScheme();
        if (scheme == null) {
            throw new IllegalArgumentException("URI with missing scheme: " + uri);
        }

        if (uri.getAuthority() == null) {
            throw new IllegalArgumentException("URI with missing authority: " + uri);
        }

        final Optional<Scheme> parsedSchemeOpt = Scheme.tryParse(scheme);
        if (!parsedSchemeOpt.isPresent()) {
            throw new IllegalArgumentException("URI with unknown scheme: " + uri);
        }

        final Scheme parsedScheme = parsedSchemeOpt.get();
        final Set<Scheme> supportedSchemes = supportedSchemes();
        if (!supportedSchemes.contains(parsedScheme)) {
            throw new IllegalArgumentException(
                    "URI with unsupported scheme: " + uri + " (expected: " + supportedSchemes + ')');
        }

        return parsedScheme;
    }

    /**
     * Creates a new {@link Endpoint} from the authority part of the specified {@link URI}.
     *
     * @param uri the {@link URI} of the server endpoint
     */
    protected static Endpoint newEndpoint(URI uri) {
        return Endpoint.parse(uri.getAuthority());
    }
}
