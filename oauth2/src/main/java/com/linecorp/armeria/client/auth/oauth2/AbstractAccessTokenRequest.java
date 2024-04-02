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

package com.linecorp.armeria.client.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.GRANT_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.SCOPE;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Objects;

import com.google.common.base.Joiner;
import com.google.common.base.MoreObjects;
import com.google.common.base.MoreObjects.ToStringHelper;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractOAuth2Request;

abstract class AbstractAccessTokenRequest extends AbstractOAuth2Request implements AccessTokenRequest {

    private static final Joiner SCOPE_JOINER = Joiner.on(' ');

    private final String grantType;
    @Nullable
    private final List<String> scopes;

    @Nullable
    private String scopeStr;

    AbstractAccessTokenRequest(@Nullable ClientAuthentication clientAuthentication, String grantType,
                               @Nullable List<String> scopes) {
        super(clientAuthentication);
        this.grantType = requireNonNull(grantType, "grantType");
        validateScopes(scopes);
        this.scopes = scopes;
    }

    private static void validateScopes(@Nullable List<String> scopes) {
        if (scopes == null) {
            return;
        }

        for (String scopeToken : scopes) {
            // scope-token = 1*( %x21 / %x23-5B / %x5D-7E )
            // https://datatracker.ietf.org/doc/html/rfc6749#section-3.3
            for (char c : scopeToken.toCharArray()) {
                // \x22 (") and \x5C (\) are not allowed in scope token.
                if (c < 0x21 || c > 0x7E || c == 0x22 || c == 0x5C) {
                    throw new IllegalArgumentException("Invalid scope token: " + scopeToken);
                }
            }
        }
    }

    @Override
    public final void doAddBodyParams(QueryParamsBuilder formBuilder) {
        requireNonNull(formBuilder, "formBuilder");
        formBuilder.add(GRANT_TYPE, grantType());
        final List<String> scopes = scopes();
        if (scopeStr != null) {
            formBuilder.add(SCOPE, scopeStr);
        } else if (scopes != null && !scopes.isEmpty()) {
            scopeStr = SCOPE_JOINER.join(scopes);
            formBuilder.add(SCOPE, scopeStr);
        }
        doAddBodyParams0(formBuilder);
    }

    abstract void doAddBodyParams0(QueryParamsBuilder formBuilder);

    @Override
    public final String grantType() {
        return grantType;
    }

    @Nullable
    @Override
    public final List<String> scopes() {
        return scopes;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AbstractAccessTokenRequest)) {
            return false;
        }
        final AbstractAccessTokenRequest that = (AbstractAccessTokenRequest) o;
        return Objects.equals(clientAuthentication(), that.clientAuthentication()) &&
               grantType.equals(that.grantType) && Objects.equals(scopes, that.scopes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientAuthentication(), grantType, scopes);
    }

    ToStringHelper toStringHelper() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("clientAuthentication", clientAuthentication())
                          .add("grantType", grantType)
                          .add("scopes", scopes);
    }
}
