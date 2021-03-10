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

import javax.annotation.Nullable;

/**
 * Implements {@link AuthorizationStatus}.
 */
class AuthorizationStatusImpl implements AuthorizationStatus {

    static final AuthorizationStatusImpl SUCCESS = new AuthorizationStatusImpl(true);
    static final AuthorizationStatusImpl FAILURE = new AuthorizationStatusImpl(false);

    private final boolean status;
    @Nullable
    private final AuthSuccessHandler successHandler;
    @Nullable
    private final AuthFailureHandler failureHandler;

    private AuthorizationStatusImpl(boolean status,
                                    @Nullable AuthSuccessHandler successHandler,
                                    @Nullable AuthFailureHandler failureHandler) {
        this.status = status;
        this.successHandler = successHandler;
        this.failureHandler = failureHandler;
    }

    private AuthorizationStatusImpl(boolean status) {
        this(status, null, null);
    }

    AuthorizationStatusImpl(@Nullable AuthSuccessHandler successHandler) {
        this(true, successHandler, null);
    }

    AuthorizationStatusImpl(@Nullable AuthFailureHandler failureHandler) {
        this(false, null, failureHandler);
    }

    @Override
    public boolean status() {
        return status;
    }

    @Nullable
    @Override
    public AuthSuccessHandler successHandler() {
        return successHandler;
    }

    @Nullable
    @Override
    public AuthFailureHandler failureHandler() {
        return failureHandler;
    }
}
