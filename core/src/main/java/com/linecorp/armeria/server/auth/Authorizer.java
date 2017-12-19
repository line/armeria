/*
 * Copyright 2017 LINE Corporation
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
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Determines whether a given {@code data} is authorized for the service registered in.
 * {@code ctx} can be used for storing authorization information about the request for use in
 * business logic. {@code data} is usually a {@link HttpRequest}
 * or token extracted from it.
 */
@FunctionalInterface
public interface Authorizer<T> {
    /**
     * Authorizes the given {@code data}.
     *
     * @return a {@link CompletionStage} that will resolve to {@code true} if the request is
     *     authorized, or {@code false} otherwise. If the future resolves exceptionally, the request
     *     will not be authorized.
     */
    CompletionStage<Boolean> authorize(ServiceRequestContext ctx, T data);
}
