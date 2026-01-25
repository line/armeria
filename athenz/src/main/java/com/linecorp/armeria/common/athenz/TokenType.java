/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.common.athenz;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * The type of Athenz token.
 */
@UnstableApi
public enum TokenType implements AthenzTokenHeader {
    /**
     * Athenz access token.
     */
    ACCESS_TOKEN(HttpHeaderNames.AUTHORIZATION, false, "Bearer"),
    /**
     * Athenz role token. {@code "Athenz-Role-Auth"} is used as the header name for this token type.
     */
    ATHENZ_ROLE_TOKEN(HttpHeaderNames.ATHENZ_ROLE_AUTH, true, null),
    /**
     * The legacy Athenz role token used by Yahoo. {@code "Yahoo-Role-Auth"} is used as the
     * header name for this token type.
     */
    YAHOO_ROLE_TOKEN(HttpHeaderNames.YAHOO_ROLE_AUTH, true, null);

    TokenType(AsciiString headerName, boolean isRoleToken, @Nullable String authScheme) {
        this.headerName = headerName;
        this.isRoleToken = isRoleToken;
        this.authScheme = authScheme;
    }

    private final AsciiString headerName;
    private final boolean isRoleToken;
    @Nullable
    private final String authScheme;

    /**
     * Returns the header name used to pass the token.
     */
    public AsciiString headerName() {
        return headerName;
    }

    /**
     * Returns whether this token type is a role token.
     */
    public boolean isRoleToken() {
        return isRoleToken;
    }

    /**
     * Returns the authentication scheme used for this token type, or {@code null} if not applicable.
     * For example, {@link TokenType#ACCESS_TOKEN} uses "Bearer" as the authentication scheme,
     * while {@link TokenType#YAHOO_ROLE_TOKEN} and {@link TokenType#ATHENZ_ROLE_TOKEN} do not use
     * any authentication scheme.
     */
    @Nullable
    public String authScheme() {
        return authScheme;
    }
}
