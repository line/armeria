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

package com.linecorp.armeria.common.auth.oauth2;

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.TOKEN_TYPE_HINT;

import java.util.Objects;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.auth.oauth2.AbstractOAuth2Request;

final class DefaultTokenOperationRequest extends AbstractOAuth2Request implements TokenOperationRequest {

    private final String token;
    @Nullable
    private final String tokenTypeHint;

    DefaultTokenOperationRequest(@Nullable ClientAuthentication clientAuthentication,
                                 String token, @Nullable String tokenTypeHint) {
        super(clientAuthentication);
        this.token = token;
        this.tokenTypeHint = tokenTypeHint;
    }

    @Override
    public void doAddBodyParams(QueryParamsBuilder formBuilder) {
        formBuilder.add(TOKEN, token);
        if (tokenTypeHint != null) {
            formBuilder.add(TOKEN_TYPE_HINT, tokenTypeHint);
        }
    }

    @Override
    public String token() {
        return token;
    }

    @Override
    public String tokenTypeHint() {
        return tokenTypeHint;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultTokenOperationRequest)) {
            return false;
        }
        final DefaultTokenOperationRequest that = (DefaultTokenOperationRequest) o;
        return Objects.equals(clientAuthentication(), that.clientAuthentication()) &&
               token.equals(that.token) && Objects.equals(tokenTypeHint, that.tokenTypeHint);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clientAuthentication(), token, tokenTypeHint);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("clientAuthentication", clientAuthentication())
                          .add("token", "****")
                          .add("tokenTypeHint", tokenTypeHint)
                          .toString();
    }
}
