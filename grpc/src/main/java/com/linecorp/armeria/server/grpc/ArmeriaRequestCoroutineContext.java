/*
 * Copyright 2021 LINE Corporation
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

import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.ServiceRequestContext;

import kotlin.coroutines.AbstractCoroutineContextElement;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ThreadContextElement;

/**
 * Propagates {@link ServiceRequestContext} over Kotlin coroutines only when using
 * <a href="https://github.com/grpc/grpc-kotlin>gRPC-Kotlin</a>.
 */
final class ArmeriaRequestCoroutineContext extends AbstractCoroutineContextElement
        implements ThreadContextElement<SafeCloseable> {

    private static final Key<ArmeriaRequestCoroutineContext> CONTEXT_KEY =
            new Key<ArmeriaRequestCoroutineContext>() {};

    private final ServiceRequestContext requestContext;

    ArmeriaRequestCoroutineContext(ServiceRequestContext requestContext) {
        super(CONTEXT_KEY);
        this.requestContext = requestContext;
    }

    @SuppressWarnings("MustBeClosedChecker")
    @Override
    public SafeCloseable updateThreadContext(CoroutineContext coroutineContext) {
        return requestContext.push();
    }

    @Override
    public void restoreThreadContext(CoroutineContext coroutineContext, SafeCloseable oldState) {
        oldState.close();
    }
}
