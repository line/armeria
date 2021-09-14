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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Provides base implementation for an {@link Authorizer} that defines custom
 * {@link AuthSuccessHandler}/{@link AuthFailureHandler}s.
 * @param <T> a type of authorization data. This typically is {@link HttpRequest}.
 */
@UnstableApi
public abstract class AbstractAuthorizerWithHandlers<T> implements Authorizer<T> {

    @Override
    public final CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data) {
        return AuthorizerUtil.authorizeAndSupplyHandlers(this, ctx, data)
                             .thenApply(status -> status != null ? status.isAuthorized() : null);
    }

    @Override
    public abstract CompletionStage<AuthorizationStatus> authorizeAndSupplyHandlers(ServiceRequestContext ctx,
                                                                                    @Nullable T data);
}
