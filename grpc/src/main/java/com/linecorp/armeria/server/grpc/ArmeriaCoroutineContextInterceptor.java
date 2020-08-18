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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.kotlin.CoroutineContextServerInterceptor;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ExecutorsKt;

final class ArmeriaCoroutineContextInterceptor extends CoroutineContextServerInterceptor {

    private final boolean useBlockingTaskExecutor;

    ArmeriaCoroutineContextInterceptor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
    }

    @Override
    public CoroutineContext coroutineContext(ServerCall<?, ?> serverCall, Metadata metadata) {
        if (useBlockingTaskExecutor) {
            return ExecutorsKt.from(ServiceRequestContext.current().blockingTaskExecutor());
        } else {
            return ExecutorsKt.from(ServiceRequestContext.current().eventLoop());
        }
    }
}
