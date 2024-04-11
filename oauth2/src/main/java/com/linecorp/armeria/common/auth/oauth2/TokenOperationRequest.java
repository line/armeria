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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An OAuth 2.0 request for token operations such as <a href="https://datatracker.ietf.org/doc/html/rfc7009">
 * OAuth 2.0 Token Revocation</a> and
 * <a href="https://datatracker.ietf.org/doc/html/rfc7662">OAuth 2.0 Token Introspection</a>
 */
@UnstableApi
public interface TokenOperationRequest extends OAuth2Request {

    /**
     * Creates a new instance of {@link TokenOperationRequest}.
     *
     * @param clientAuthentication the client authentication
     * @param token the token that the client wants to operate
     * @param tokenTypeHint the hint about the type of the token that the client wants to operate
     */
    static TokenOperationRequest of(@Nullable ClientAuthentication clientAuthentication,
                                    String token, @Nullable String tokenTypeHint) {
        requireNonNull(token, "token");
        return new DefaultTokenOperationRequest(clientAuthentication, token, tokenTypeHint);
    }

    /**
     * The token that the client wants to operate.
     */
    String token();

    /**
     * The hint about the type of the token that the client wants to operate.
     */
    @Nullable
    String tokenTypeHint();
}
