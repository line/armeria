/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.auth.oauth2;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeadersBuilder;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.auth.oauth2.CaseUtil;

/**
 * Provides client authorization for the OAuth 2.0 requests,
 * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
 * For example:
 * <pre>{@code
 * Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3
 * }</pre>
 * Fetches authorization source from the designated authorization or credentials supplier, which
 * might be facilitated by a secure Secret provider. Will fetch the authorization source for each
 * request. Therefore the designated supplier must cache the value in order to avoid unnecessary
 * network hops.
 * The authorization source might either provide complete authorization token or client credentials.
 *
 * @deprecated Use {@link ClientAuthentication} instead.
 */
@Deprecated
public final class ClientAuthorization {

    private static final String DEFAULT_AUTHORIZATION_TYPE = "Basic";
    private static final char AUTHORIZATION_SEPARATOR = ' ';
    private static final char CREDENTIALS_SEPARATOR = ':';

    private static final String CLIENT_ID = "client_id";
    private static final String CLIENT_SECRET = "client_secret";

    private final String authorizationType;
    @Nullable
    private final Supplier<String> authorizationSupplier;
    @Nullable
    private final Supplier<? extends Map.Entry<String, String>> credentialsSupplier;

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     * @deprecated Use {@link ClientAuthentication#ofAuthorization(String, String)} instead.
     */
    @Deprecated
    public static ClientAuthorization ofAuthorization(
            Supplier<String> authorizationSupplier, String authorizationType) {
        return new ClientAuthorization(requireNonNull(authorizationSupplier, "authorizationSupplier"),
                                       null, requireNonNull(authorizationType, "authorizationType"));
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
     * {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param authorizationSupplier A supplier of encoded client authorization token.
     *
     * @deprecated Use {@link ClientAuthentication#ofBasic(String)}} instead.
     */
    @Deprecated
    public static ClientAuthorization ofBasicAuthorization(
            Supplier<String> authorizationSupplier) {
        return new ClientAuthorization(requireNonNull(authorizationSupplier, "authorizationSupplier"),
                                       null, null);
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     * @param authorizationType One of the registered HTTP authentication schemes as per
     *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
     *                          HTTP Authentication Scheme Registry</a>.
     * @deprecated Use {@link ClientAuthentication#ofClientPassword(String, String)} or
     *             {@link ClientAuthentication#ofAuthorization(String, String)} instead.
     */
    @Deprecated
    public static ClientAuthorization ofCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
        return new ClientAuthorization(null,
                                       requireNonNull(credentialsSupplier, "credentialsSupplier"),
                                       requireNonNull(authorizationType, "authorizationType"));
    }

    /**
     * Provides client authorization for the OAuth 2.0 requests based on client credentials and
     * {@code Basic} authorization type,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
     *
     * @param credentialsSupplier A supplier of client credentials.
     *
     * @deprecated Use {@link ClientAuthentication#ofClientPassword(String, String)} instead.
     */
    @Deprecated
    public static ClientAuthorization ofCredentials(
            Supplier<? extends Map.Entry<String, String>> credentialsSupplier) {
        return new ClientAuthorization(null,
                                       requireNonNull(credentialsSupplier, "credentialsSupplier"), null);
    }

    private ClientAuthorization(@Nullable Supplier<String> authorizationSupplier,
                                @Nullable Supplier<? extends Map.Entry<String, String>> credentialsSupplier,
                                @Nullable String authorizationType) {
        if (authorizationSupplier == null && credentialsSupplier == null) {
            throw new NullPointerException("authorizationSupplier && credentialsSupplier");
        }
        this.authorizationSupplier = authorizationSupplier;
        this.credentialsSupplier = credentialsSupplier;
        this.authorizationType =
                authorizationType == null ? DEFAULT_AUTHORIZATION_TYPE : authorizationType;
    }

    private String composeAuthorizationString() {
        final String clientAuthorization;
        if (authorizationSupplier != null) {
            clientAuthorization = authorizationSupplier.get();
        } else if (credentialsSupplier != null) {
            final Map.Entry<String, String> clientCredentials = credentialsSupplier.get();
            clientAuthorization = encodeClientCredentials(clientCredentials.getKey(),
                                                          clientCredentials.getValue());
        } else {
            // we should not get here
            throw new NullPointerException("authorizationSupplier && credentialsSupplier");
        }
        return clientAuthorization;
    }

    /**
     * Fetches client authorization token or client credentials from the supplier and composes client
     * {@code Authorization} header value,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>:
     * <pre>{@code
     * Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3
     * }</pre>.
     *
     * @return encoded client {@code Authorization} header value.
     */
    public String asHeaderValue() {
        return CaseUtil.firstUpperAllLowerCase(authorizationType) +
               AUTHORIZATION_SEPARATOR + composeAuthorizationString();
    }

    /**
     * Fetches client credentials from the supplier and composes required body parameters,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>:
     * <pre>{@code
     * client_id=s6BhdRkqt3&client_secret=7Fjfp0ZBr1KtDRbnfVdmIw
     * }</pre>.
     * The client MAY omit the {@code client_secret} parameter if the client secret is an empty string.
     */
    public void addAsBodyParameters(QueryParamsBuilder formBuilder) {
        requireNonNull(credentialsSupplier, "credentialsSupplier");
        final Map.Entry<String, String> clientCredentials = credentialsSupplier.get();
        formBuilder.add(CLIENT_ID, requireNonNull(clientCredentials.getKey(), CLIENT_ID));
        final String clientSecret = clientCredentials.getValue();
        if (clientSecret != null && !clientSecret.isEmpty()) {
            formBuilder.add(CLIENT_SECRET, clientSecret);
        }
    }

    /**
     * Fetches client credentials from the supplier and composes required body parameters,
     * as per <a href="https://datatracker.ietf.org/doc/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>:
     * <pre>{@code
     * client_id=s6BhdRkqt3&client_secret=7Fjfp0ZBr1KtDRbnfVdmIw
     * }</pre>.
     * The client MAY omit the {@code client_secret} parameter if the client secret is an empty string.
     *
     * @return encoded client credentials request body parameters as {@link QueryParams}.
     */
    public QueryParams asBodyParameters() {
        final QueryParamsBuilder formBuilder = QueryParams.builder();
        addAsBodyParameters(formBuilder);
        return formBuilder.build();
    }

    /**
     * Converts this {@link ClientAuthorization} to a {@link ClientAuthentication}.
     */
    public ClientAuthentication toClientAuthentication() {
        return new ClientAuthentication() {
            @Override
            public void addAsHeaders(HttpHeadersBuilder headersBuilder) {
                headersBuilder.add(HttpHeaderNames.AUTHORIZATION, asHeaderValue());
            }

            @Override
            public void addAsBodyParams(QueryParamsBuilder formBuilder) {
                // ClientAuthorization is not used as body parameters under the current API usage.
            }
        };
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("type", authorizationType)
                          .add("source",
                               authorizationSupplier != null ? "authorization"
                                                             : credentialsSupplier != null ? "credentials"
                                                                                           : null)
                          .toString();
    }

    private static String encodeClientCredentials(String clientId, String clientSecret) {
        return Base64.getEncoder()
                     .encodeToString(
                             (clientId + CREDENTIALS_SEPARATOR + clientSecret)
                                     .getBytes(StandardCharsets.UTF_8));
    }
}
