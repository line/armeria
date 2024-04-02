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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.PASSWORD;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.PASSWORD_GRANT_TYPE;
import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.USER_NAME;

import java.util.List;
import java.util.Objects;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

final class ResourceOwnerPasswordAccessTokenRequest extends AbstractAccessTokenRequest {

    private final String username;
    private final String password;

    ResourceOwnerPasswordAccessTokenRequest(String username, String password,
                                            @Nullable ClientAuthentication clientAuthentication,
                                            @Nullable List<String> scopes) {
        // The client authentication is optional for the resource owner password credentials grant.
        super(clientAuthentication, PASSWORD_GRANT_TYPE, scopes);
        this.username = username;
        this.password = password;
    }

    @Override
    void doAddBodyParams0(QueryParamsBuilder formBuilder) {
        formBuilder.add(USER_NAME, username);
        formBuilder.add(PASSWORD, password);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ResourceOwnerPasswordAccessTokenRequest)) {
            return false;
        }

        final ResourceOwnerPasswordAccessTokenRequest that = (ResourceOwnerPasswordAccessTokenRequest) o;
        return super.equals(o) && username.equals(that.username) && password.equals(that.password);
    }

    @Override
    public int hashCode() {
        return super.hashCode() * 31 + Objects.hash(username, password);
    }

    @Override
    public String toString() {
        return toStringHelper()
                .add("username", username)
                .add("password", "****")
                .toString();
    }
}
