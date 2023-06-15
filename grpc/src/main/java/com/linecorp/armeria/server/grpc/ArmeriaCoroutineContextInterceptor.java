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

import static com.google.common.base.Preconditions.checkState;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.kotlin.CoroutineContextServerInterceptor;
import kotlin.coroutines.CoroutineContext;
import kotlinx.coroutines.ExecutorsKt;

final class ArmeriaCoroutineContextInterceptor extends CoroutineContextServerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(ArmeriaCoroutineContextInterceptor.class);

    private static final List<?> COROUTINE_CONTEXT_PROVIDERS;
    @Nullable
    private static final MethodHandle PROVIDE_METHOD;

    static {
        List<?> providers = ImmutableList.of();
        MethodHandle provideMethod = null;
        try {
            final Class<?> clazz = Class.forName(
                    RequestContext.class.getPackage().getName() + ".kotlin.CoroutineContextProvider");
            providers = ImmutableList.copyOf(
                    ServiceLoader.load(clazz, ArmeriaCoroutineContextInterceptor.class.getClassLoader()));
            final MethodHandles.Lookup publicLookup = MethodHandles.publicLookup();
            final MethodType mt = MethodType.methodType(CoroutineContext.class, ServiceRequestContext.class);
            provideMethod = publicLookup.findVirtual(clazz, "provide", mt);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException ignored) {
        }

        if (!providers.isEmpty()) {
            logger.debug("Available CoroutineContextProviders: {}", providers);
        }
        COROUTINE_CONTEXT_PROVIDERS = providers;
        PROVIDE_METHOD = provideMethod;
    }

    private final boolean useBlockingTaskExecutor;

    ArmeriaCoroutineContextInterceptor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
    }

    @Override
    public CoroutineContext coroutineContext(ServerCall<?, ?> serverCall, Metadata metadata) {
        final ServiceRequestContext ctx = ServerCallUtil.findRequestContext(serverCall);
        checkState(ctx != null, "Failed to find the current %s from %s",
                   ServiceRequestContext.class.getSimpleName(), serverCall);
        CoroutineContext coroutineContext = new ArmeriaRequestCoroutineContext(ctx);

        if (PROVIDE_METHOD != null && !COROUTINE_CONTEXT_PROVIDERS.isEmpty()) {
            for (Object provider : COROUTINE_CONTEXT_PROVIDERS) {
                try {
                    coroutineContext =
                            coroutineContext.plus((CoroutineContext) PROVIDE_METHOD.invoke(provider, ctx));
                } catch (Throwable e) {
                    throw new IllegalStateException("Failed to invoke CoroutineContextProvider#provide()", e);
                }
            }
            return coroutineContext;
        } else {
            // No custom context is specified. Use an event loop or a block task
            // executor as the default Coroutine dispatcher.
            final ScheduledExecutorService executor;
            if (useBlockingTaskExecutor) {
                executor = ctx.blockingTaskExecutor().withoutContext();
            } else {
                executor = ctx.eventLoop().withoutContext();
            }
            return ExecutorsKt.from(executor).plus(coroutineContext);
        }
    }
}
