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

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

/**
 * Provides client authorization for the OAuth 2.0 requests,
 * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
 * For example:
 * <pre>{@code
 * Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3
 * }</pre>
 * Fetches authorization source from the designated authorization or credentials supplier, which
 * might be facilitated by a secure Secret provider. Will fetch the authorization source for each
 * request. Therefore the designated supplier must cache the value in order to avoid unnecessary
 * network hops.
 * The authorization source might either provide complete authorization token or client credentials.
 */
public final class ClientAuthorization {

  private static final String DEFAULT_AUTHORIZATION_TYPE = "Basic";
  private static final String AUTHORIZATION_SEPARATOR = " ";
  private static final String CREDENTIALS_SEPARATOR = ":";

  private static final String CLIENT_ID = "client_id";
  private static final String CLIENT_SECRET = "client_secret";
  private static final char FORM_ENTRY_SEPARATOR = '=';
  private static final char FORM_TUPLE_SEPARATOR = '&';

  private final String authorizationType;
  @Nullable
  private final Supplier<String> authorizationSupplier;
  @Nullable
  private final Supplier<Map.Entry<String, String>> credentialsSupplier;

  /**
   * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
   * authorization type,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
   *
   * @param authorizationSupplier A supplier of encoded client authorization token.
   * @param authorizationType One of the registered HTTP authentication schemes as per
   *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
   *                          HTTP Authentication Scheme Registry</a>.
   */
  public static ClientAuthorization ofAuthorization(
      Supplier<String> authorizationSupplier, String authorizationType) {
    return new ClientAuthorization(requireNonNull(authorizationSupplier, "authorizationSupplier"),
        null, requireNonNull(authorizationType, "authorizationType"));
  }

  /**
   * Provides client authorization for the OAuth 2.0 requests based on encoded authorization token and
   * {@code Basic} authorization type,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
   *
   * @param authorizationSupplier A supplier of encoded client authorization token.
   */
  public static ClientAuthorization ofBasicAuthorization(
      Supplier<String> authorizationSupplier) {
    return new ClientAuthorization(requireNonNull(authorizationSupplier, "authorizationSupplier"),
        null, null);
  }

  /**
   * Provides client authorization for the OAuth 2.0 requests based on client credentials and
   * authorization type,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
   *
   * @param credentialsSupplier A supplier of client credentials.
   * @param authorizationType One of the registered HTTP authentication schemes as per
   *                          <a href="https://www.iana.org/assignments/http-authschemes/http-authschemes.xhtml">
   *                          HTTP Authentication Scheme Registry</a>.
   */
  public static ClientAuthorization ofCredentials(
      Supplier<Map.Entry<String, String>> credentialsSupplier, String authorizationType) {
    return new ClientAuthorization(null,
        requireNonNull(credentialsSupplier, "credentialsSupplier"),
        requireNonNull(authorizationType, "authorizationType"));
  }

  /**
   * Provides client authorization for the OAuth 2.0 requests based on client credentials and
   * {@code Basic} authorization type,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>.
   *
   * @param credentialsSupplier A supplier of client credentials.
   */
  public static ClientAuthorization ofCredentials(
      Supplier<Map.Entry<String, String>> credentialsSupplier) {
    return new ClientAuthorization(null,
        requireNonNull(credentialsSupplier, "credentialsSupplier"), null);
  }

  private ClientAuthorization(@Nullable Supplier<String> authorizationSupplier,
      @Nullable Supplier<Map.Entry<String, String>> credentialsSupplier,
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
      clientAuthorization = encodeClientCredentials(clientCredentials.getKey(), clientCredentials.getValue());
    } else {
      // we should not get here
      throw new NullPointerException("authorizationSupplier && credentialsSupplier");
    }
    return clientAuthorization;
  }

  /**
   * Fetches client authorization token or client credentials from the supplier and composes client
   * {@code Authorization} header value,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>:
   * <pre>{@code
   * Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3
   * }</pre>.
   *
   * @return encoded client {@code Authorization} header value.
   */
  public String authorizationHeaderValue() {
    return String.join(AUTHORIZATION_SEPARATOR,
                       CaseUtil.firstUpperAllLowerCase(authorizationType), composeAuthorizationString());
  }

  /**
   * Fetches client credentials from the supplier and composes required body parameters,
   * as per <a href="https://tools.ietf.org/html/rfc6749#section-2.3">[RFC6749], Section 2.3</a>:
   * <pre>{@code
   * client_id=s6BhdRkqt3&client_secret=7Fjfp0ZBr1KtDRbnfVdmIw
   * }</pre>.
   *
   * @return encoded client credentials request body parameters.
   */
  public String credentialsBodyParameters() {
    requireNonNull(credentialsSupplier, "credentialsSupplier");
    final Map.Entry<String, String> clientCredentials = credentialsSupplier.get();
    final StringBuilder builder = new StringBuilder()
        .append(CLIENT_ID).append(FORM_ENTRY_SEPARATOR)
        .append(urlEncode(clientCredentials.getKey()))
        .append(FORM_TUPLE_SEPARATOR)
        .append(CLIENT_SECRET).append(FORM_ENTRY_SEPARATOR)
        .append(urlEncode(clientCredentials.getValue()));
    return builder.toString();
  }

  private static String encodeClientCredentials(String clientId, String clientSecret) {
      return Base64.getEncoder()
                   .encodeToString(
                           String.join(CREDENTIALS_SEPARATOR, clientId, clientSecret)
                                 .getBytes(StandardCharsets.UTF_8));
  }

  private static String urlEncode(String s) {
    try {
      return URLEncoder.encode(s, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new RuntimeException(e);
    }
  }
}
