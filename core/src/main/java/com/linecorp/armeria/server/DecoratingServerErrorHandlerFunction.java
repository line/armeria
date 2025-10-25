/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.server;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.common.HttpResponse;

/**
 * A function to decorate a {@link ServerErrorHandler} with additional behavior. It is typically used to inject
 * additional logic before or after the specified {@link ServerErrorHandler} execution.
 */
interface DecoratingServerErrorHandlerFunction {
    @Nullable
    HttpResponse onServiceException(ServerErrorHandler delegate, ServiceRequestContext ctx, Throwable cause);
}
