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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.linecorp.armeria.server.saml.SamlPortConfig.validatePort;
import static java.util.Objects.requireNonNull;

import java.net.URI;
import java.net.URISyntaxException;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A SAML service URL and its binding protocol.
 */
public final class SamlEndpoint {
    /**
     * Creates a {@link SamlEndpoint} of the specified {@code uri} and the HTTP Redirect binding protocol.
     */
    public static SamlEndpoint ofHttpRedirect(String uri) {
        requireNonNull(uri, "uri");
        try {
            return ofHttpRedirect(new URI(uri));
        } catch (URISyntaxException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * Creates a {@link SamlEndpoint} of the specified {@code uri} and the HTTP Redirect binding protocol.
     */
    public static SamlEndpoint ofHttpRedirect(URI uri) {
        requireNonNull(uri, "uri");
        return new SamlEndpoint(uri, SamlBindingProtocol.HTTP_REDIRECT);
    }

    /**
     * Creates a {@link SamlEndpoint} of the specified {@code uri} and the HTTP POST binding protocol.
     */
    public static SamlEndpoint ofHttpPost(String uri) {
        requireNonNull(uri, "uri");
        try {
            return ofHttpPost(new URI(uri));
        } catch (URISyntaxException e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    /**
     * Creates a {@link SamlEndpoint} of the specified {@code uri} and the HTTP POST binding protocol.
     */
    public static SamlEndpoint ofHttpPost(URI uri) {
        requireNonNull(uri, "uri");
        return new SamlEndpoint(uri, SamlBindingProtocol.HTTP_POST);
    }

    private final URI uri;
    private final SamlBindingProtocol bindingProtocol;
    private final String uriAsString;

    private SamlEndpoint(URI uri, SamlBindingProtocol bindingProtocol) {
        this.uri = uri;
        this.bindingProtocol = bindingProtocol;
        uriAsString = uri.toString();
    }

    /**
     * Returns a {@link URI} of this endpoint.
     */
    public URI uri() {
        return uri;
    }

    /**
     * Returns a {@link URI} of this endpoint as a string.
     */
    public String toUriString() {
        return uriAsString;
    }

    /**
     * Returns a {@link URI} of this endpoint as a string. The omitted values in the {@link URI} will be
     * replaced with the specified default values, such as {@code defaultScheme}, {@code defaultHostname}
     * and {@code defaultPort}.
     */
    String toUriString(String defaultScheme, String defaultHostname, int defaultPort) {
        requireNonNull(defaultScheme, "defaultScheme");
        requireNonNull(defaultHostname, "defaultHostname");
        validatePort(defaultPort);

        final StringBuilder sb = new StringBuilder();
        sb.append(firstNonNull(uri.getScheme(), defaultScheme)).append("://")
          .append(firstNonNull(uri.getHost(), defaultHostname)).append(':')
          .append(uri.getPort() > 0 ? uri.getPort() : defaultPort)
          .append(uri.getPath());
        return sb.toString();
    }

    /**
     * Returns a {@link SamlBindingProtocol} of this endpoint.
     */
    public SamlBindingProtocol bindingProtocol() {
        return bindingProtocol;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SamlEndpoint)) {
            return false;
        }
        final SamlEndpoint that = (SamlEndpoint) o;
        return uri().equals(that.uri()) && bindingProtocol() == that.bindingProtocol();
    }

    @Override
    public int hashCode() {
        return uri().hashCode() * 31 + bindingProtocol().hashCode();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("uri", uri)
                          .add("bindingProtocol", bindingProtocol)
                          .toString();
    }
}
