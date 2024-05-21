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

import static com.linecorp.armeria.internal.common.auth.oauth2.OAuth2Constants.CLIENT_CREDENTIALS_GRANT_TYPE;

import java.util.List;

import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.auth.oauth2.ClientAuthentication;

final class ClientCredentialsAccessTokenRequest extends AbstractAccessTokenRequest {

    ClientCredentialsAccessTokenRequest(ClientAuthentication clientAuthentication,
                                        @Nullable List<String> scopes) {
        super(clientAuthentication, CLIENT_CREDENTIALS_GRANT_TYPE, scopes);
    }

    @Override
    void doAddBodyParams0(QueryParamsBuilder formBuilder) {
        // No additional body params are required for client credentials grant.
    }
}
