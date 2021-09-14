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

package com.linecorp.armeria.server.auth;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Provides a status of the request authorization operation, optionally combined with {@link AuthSuccessHandler}
 * and {@link AuthFailureHandler} to facilitate custom status handling.
 */
@UnstableApi
public interface AuthorizationStatus {

    /**
     * Creates {@link AuthorizationStatus} based on the given boolean status, with neither
     * {@link AuthFailureHandler} nor {@link AuthSuccessHandler} defined.
     *
     * @param isAuthorized Whether the request was authorized or not.
     */
    static AuthorizationStatus of(boolean isAuthorized) {
        return isAuthorized ? AuthorizationStatusImpl.SUCCESS : AuthorizationStatusImpl.FAILURE;
    }

    /**
     * Default Authorization Success status with no {@link AuthSuccessHandler} defined.
     */
    static AuthorizationStatus ofSuccess() {
        return AuthorizationStatusImpl.SUCCESS;
    }

    /**
     * Creates {@link AuthorizationStatus} success status with optional {@link AuthSuccessHandler}.
     */
    static AuthorizationStatus ofSuccess(@Nullable AuthSuccessHandler successHandler) {
        return new AuthorizationStatusImpl(successHandler);
    }

    /**
     * Creates {@link AuthorizationStatus} success status with optional {@link AuthFailureHandler}.
     */
    static AuthorizationStatus ofFailure(@Nullable AuthFailureHandler failureHandler) {
        return new AuthorizationStatusImpl(failureHandler);
    }

    /**
     * A status of the request authorization operation.
     * @return {@code true} if the request is authorized, or {@code false} otherwise.
     */
    boolean isAuthorized();

    /**
     * Returns the {@link AuthSuccessHandler} to handle successfully authorized requests.
     * It may return {@code null}, which indicates to use {@link AuthService}'s default handling.
     * @return An instance of {@link AuthSuccessHandler} to handle successfully authorized requests
     *         or {@code null} to rely on the default handling.
     */
    @Nullable
    AuthSuccessHandler successHandler();

    /**
     * Returns the {@link AuthFailureHandler} to handle unauthorized requests.
     * It may return {@code null}, which indicates to use {@link AuthService}'s default handling.
     * @return An instance of {@link AuthFailureHandler} to handle unauthorized requests
     *         or {@code null} to rely on the default handling.
     */
    @Nullable
    AuthFailureHandler failureHandler();
}
