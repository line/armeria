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

import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A function to decorate a {@link ServerErrorHandler} or a {@link ServiceErrorHandler} with additional
 * behavior. Implementations can wrap error handlers to add pre-processing, post-processing, or
 * conditional logic around error handling. The decorator receives the delegate error handler and
 * can decide whether and when to invoke it.
 */
interface DecoratingErrorHandlerFunction {
    @Nullable
    HttpResponse onServiceException(ServerErrorHandler delegate, ServiceRequestContext ctx, Throwable cause);

    @Nullable
    HttpResponse onServiceException(ServiceErrorHandler delegate, ServiceRequestContext ctx, Throwable cause);
}
