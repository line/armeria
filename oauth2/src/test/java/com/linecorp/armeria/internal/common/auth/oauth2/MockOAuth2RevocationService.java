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

package com.linecorp.armeria.internal.common.auth.oauth2;

import java.util.Optional;

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.annotation.Consumes;
import com.linecorp.armeria.server.annotation.Default;
import com.linecorp.armeria.server.annotation.Header;
import com.linecorp.armeria.server.annotation.Param;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.annotation.decorator.LoggingDecorator;

@LoggingDecorator
public class MockOAuth2RevocationService extends MockOAuth2Service {

    @Post("/token/")
    @Consumes("application/x-www-form-urlencoded")
    public HttpResponse handleIntrospect(
            @Header("Authorization") Optional<String> auth,
            @Param("token") Optional<String> token,
            @Param("token_type_hint") @Default("access_token") String tokenTypeHint) {

        // first, check "Authorization"
        final HttpResponse response = verifyClientCredentials(auth, "token revocation");
        if (response != null) {
            return response; // UNAUTHORIZED or BAD_REQUEST
        }

        // find the token
        if (!token.isPresent()) {
            return HttpResponse.of(HttpStatus.BAD_REQUEST, MediaType.JSON_UTF_8, INVALID_REQUEST);
        }
        return HttpResponse.of(HttpStatus.OK);
    }
}
