/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.athenz;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * Defines the configuration for an Athenz token header.
 *
 * <p>This interface allows you to configure how Athenz tokens are passed in HTTP headers,
 * supporting both predefined token types ({@link TokenType}) and custom header configurations.
 *
 * <p>Use this interface to:
 * <ul>
 *   <li>Specify custom header names for Athenz tokens</li>
 *   <li>Define authentication schemes (e.g., "Bearer", or none)</li>
 *   <li>Indicate whether the token is a role token or access token</li>
 * </ul>
 *
 * <p>Example of a custom implementation:
 * <pre>{@code
 * import com.linecorp.armeria.common.athenz.AthenzTokenHeader;
 * import io.netty.util.AsciiString;
 *
 * public class CustomAthenzHeader implements AthenzTokenHeader {
 *
 *     private static final AsciiString HEADER_NAME = AsciiString.of("X-Custom-Athenz-Token");
 *
 *     @Override
 *     public String name() {
 *         return "CUSTOM_TOKEN";
 *     }
 *
 *     @Override
 *     public AsciiString headerName() {
 *         return HEADER_NAME;
 *     }
 *
 *     @Override
 *     public String authScheme() {
 *         // Return null if no authentication scheme is needed
 *         return null;
 *     }
 *
 *     @Override
 *     public boolean isRoleToken() {
 *         // Return true if this is a role token, false if it's an access token
 *         return false;
 *     }
 * }
 *
 * // Usage example:
 * AthenzClient.builder(ztsBaseClient)
 *             .domainName("my-domain")
 *             .tokenHeader(new CustomAthenzHeader())
 *             .newDecorator();
 * }</pre>
 *
 * @see TokenType for predefined token header configurations
 */
@UnstableApi
public interface AthenzTokenHeader {

    /**
     * Returns an {@link AthenzTokenHeader} for Access Token (OAuth2 Bearer token).
     *
     * <p>This is a convenience factory method that returns {@link TokenType#ACCESS_TOKEN}.
     * It allows users to discover predefined token types through IDE autocomplete
     * without needing to know about the {@link TokenType} enum.
     *
     * @return {@link TokenType#ACCESS_TOKEN}
     */
    static AthenzTokenHeader ofAccessToken() {
        return TokenType.ACCESS_TOKEN;
    }

    /**
     * Returns an {@link AthenzTokenHeader} for Athenz Role Token.
     *
     * <p>This is a convenience factory method that returns {@link TokenType#ATHENZ_ROLE_TOKEN}.
     * It allows users to discover predefined token types through IDE autocomplete
     * without needing to know about the {@link TokenType} enum.
     *
     * @return {@link TokenType#ATHENZ_ROLE_TOKEN}
     */
    static AthenzTokenHeader ofAthenzRoleToken() {
        return TokenType.ATHENZ_ROLE_TOKEN;
    }

    /**
     * Returns an {@link AthenzTokenHeader} for Yahoo Role Token (legacy).
     *
     * <p>This is a convenience factory method that returns {@link TokenType#YAHOO_ROLE_TOKEN}.
     * It allows users to discover predefined token types through IDE autocomplete
     * without needing to know about the {@link TokenType} enum.
     *
     * @return {@link TokenType#YAHOO_ROLE_TOKEN}
     */
    static AthenzTokenHeader ofYahooRoleToken() {
        return TokenType.YAHOO_ROLE_TOKEN;
    }

    /**
     * Returns the name of this token header configuration.
     *
     * <p>This name is used for identification purposes in logs and metrics.
     * For example, {@link TokenType#ACCESS_TOKEN} returns "ACCESS_TOKEN".
     *
     * @return the name of this token header configuration
     */
    String name();

    /**
     * Returns the HTTP header name used to pass the Athenz token.
     *
     * <p>For example:
     * <ul>
     *   <li>{@link TokenType#ACCESS_TOKEN} uses {@code "authorization"}</li>
     *   <li>{@link TokenType#ATHENZ_ROLE_TOKEN} uses {@code "Athenz-Role-Auth"}</li>
     *   <li>{@link TokenType#YAHOO_ROLE_TOKEN} uses {@code "Yahoo-Role-Auth"}</li>
     * </ul>
     *
     * @return the HTTP header name for the token
     */
    AsciiString headerName();

    /**
     * Returns the authentication scheme used for this token type, or {@code null} if not applicable.
     *
     * <p>The authentication scheme is prepended to the token value when setting the header.
     * For example, {@link TokenType#ACCESS_TOKEN} uses "Bearer" as the authentication scheme,
     * resulting in a header value like: {@code "Bearer <token-value>"}.
     *
     * <p>Role tokens typically do not use an authentication scheme and return {@code null}.
     *
     * @return the authentication scheme, or {@code null} if no scheme is used
     */
    @Nullable
    String authScheme();

    /**
     * Returns whether this token type represents a role token.
     *
     * <p>This affects how the token is obtained from the Athenz Token Service (ZTS):
     * <ul>
     *   <li>Role tokens ({@code true}) are obtained via the role token endpoint</li>
     *   <li>Access tokens ({@code false}) are obtained via the access token endpoint</li>
     * </ul>
     *
     * @return {@code true} if this is a role token, {@code false} if it's an access token
     */
    boolean isRoleToken();
}
