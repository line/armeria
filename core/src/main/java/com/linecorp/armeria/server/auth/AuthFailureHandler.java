/*
 * Copyright 2018 LINE Corporation
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

import com.google.errorprone.annotations.CheckReturnValue;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Service;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * A callback which is invoked to handle an authorization failure indicated by {@link Authorizer}.
 *
 * @see AuthServiceBuilder#onFailure(AuthFailureHandler)
 */
@FunctionalInterface
public interface AuthFailureHandler {
    /**
     * Invoked when the authorization of the specified {@link HttpRequest} has failed.
     *
     * @param delegate the next {@link Service} in the decoration chain
     * @param ctx the {@link ServiceRequestContext}
     * @param req the {@link HttpRequest} being handled
     * @param cause {@code null} if the {@link HttpRequest} has been rejected by the {@link Authorizer}.
     *              non-{@code null} if the {@link Authorizer} raised an {@link Exception}.
     */
    @CheckReturnValue
    HttpResponse authFailed(HttpService delegate, ServiceRequestContext ctx,
                            HttpRequest req, @Nullable Throwable cause) throws Exception;
}
