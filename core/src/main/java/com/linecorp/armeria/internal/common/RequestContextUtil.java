/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.common;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.MapMaker;
import com.google.errorprone.annotations.MustBeClosed;

import com.linecorp.armeria.client.DefaultClientRequestContext;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestContextStorageProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.server.DefaultServiceRequestContext;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

/**
 * Utilities for {@link RequestContext}.
 */
public final class RequestContextUtil {

    private static final Logger logger = LoggerFactory.getLogger(RequestContextUtil.class);

    private static final SafeCloseable noopSafeCloseable = () -> { /* no-op */ };

    /**
     * Keeps track of the {@link Thread}s reported by
     * {@link #newIllegalContextPushingException(RequestContext, RequestContext)}.
     */
    private static final Set<Thread> REPORTED_THREADS =
            Collections.newSetFromMap(new MapMaker().weakKeys().makeMap());

    private static RequestContextStorage requestContextStorage;

    static {
        final List<RequestContextStorageProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(RequestContextStorageProvider.class));
        final String providerFqcn = Flags.requestContextStorageProvider();
        if (!providers.isEmpty()) {

            RequestContextStorageProvider provider = null;
            if (providers.size() > 1) {
                if (providerFqcn == null) {
                    throw new IllegalStateException(
                            "Found more than one " + RequestContextStorageProvider.class.getSimpleName() +
                            ". You must specify -Dcom.linecorp.armeria.requestContextStorageProvider=<FQCN>." +
                            " providers: " + providers);
                }

                for (RequestContextStorageProvider candidate : providers) {
                    if (candidate.getClass().getName().equals(providerFqcn)) {
                        if (provider != null) {
                            throw new IllegalStateException(
                                    providerFqcn + " matches more than one " +
                                    RequestContextStorageProvider.class.getSimpleName() + ". providers: " +
                                    providers);
                        } else {
                            provider = candidate;
                        }
                    }
                }
                if (provider == null) {
                    throw new IllegalStateException(
                            providerFqcn + " does not match any " +
                            RequestContextStorageProvider.class.getSimpleName() + ". providers: " + providers);
                }
            } else {
                provider = providers.get(0);
                if (logger.isInfoEnabled()) {
                    logger.info("Using {} as a {}",
                                provider.getClass().getSimpleName(),
                                RequestContextStorageProvider.class.getSimpleName());
                }
            }

            try {
                requestContextStorage = provider.newStorage();
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to create context storage. provider: " + provider, t);
            }
        } else {
            requestContextStorage = RequestContextStorage.threadLocal();
        }
    }

    /**
     * Invoked to initialize this class earlier than when an {@link HttpRequest} is received or sent.
     */
    public static void init() { /* no-op */ }

    /**
     * Returns the {@link SafeCloseable} which doesn't do anything.
     */
    @MustBeClosed
    public static SafeCloseable noopSafeCloseable() {
        return noopSafeCloseable;
    }

    /**
     * Returns an {@link IllegalStateException} which is raised when pushing a context from
     * the unexpected thread or forgetting to close the previous context.
     */
    public static IllegalStateException newIllegalContextPushingException(
            RequestContext newCtx, RequestContext oldCtx) {
        requireNonNull(newCtx, "newCtx");
        requireNonNull(oldCtx, "oldCtx");
        final IllegalStateException ex = new IllegalStateException(
                "Trying to call object wrapped with context " + newCtx + ", but context is currently " +
                "set to " + oldCtx + ". This means the callback was called from " +
                "unexpected thread or forgetting to close previous context.");
        if (REPORTED_THREADS.add(Thread.currentThread())) {
            logger.warn("An error occurred while pushing a context", ex);
        }
        return ex;
    }

    /**
     * Returns an {@link IllegalStateException} which is raised when popping a context from
     * the unexpected thread or forgetting to close the previous context.
     */
    public static IllegalStateException newIllegalContextPoppingException(
            RequestContext currentCtx, RequestContext contextInStorage) {
        requireNonNull(currentCtx, "currentCtx");
        requireNonNull(contextInStorage, "contextInStorage");
        final IllegalStateException ex = new IllegalStateException(
                "The currentCtx " + currentCtx + " is not the same as the context in the storage: " +
                contextInStorage + ". This means the callback was called from " +
                "unexpected thread or forgetting to close previous context.");
        if (REPORTED_THREADS.add(Thread.currentThread())) {
            logger.warn("An error occurred while popping a context", ex);
        }
        return ex;
    }

    public static void hook(Function<? super RequestContextStorage, ? extends RequestContextStorage> function) {
        final RequestContextStorage newStorage = function.apply(requestContextStorage);
        checkState(newStorage != null, "function.apply() returned null: %s", function);
        requestContextStorage = newStorage;
    }

    /**
     * Returns the current {@link RequestContext} in the {@link RequestContextStorage}.
     */
    @Nullable
    public static <T extends RequestContext> T get() {
        return requestContextStorage.currentOrNull();
    }

    /**
     * Sets the specified {@link RequestContext} in the {@link RequestContextStorage} and
     * returns the old {@link RequestContext}.
     */
    @Nullable
    public static <T extends RequestContext> T getAndSet(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return requestContextStorage.push(ctx);
    }

    /**
     * Removes the {@link RequestContext} in the {@link RequestContextStorage} if exists and returns
     * {@link SafeCloseable} which pushes the {@link RequestContext} back to the {@link RequestContextStorage}.
     *
     * <p>Because this method pops the {@link RequestContext} arbitrarily, it shouldn't be used in
     * most cases. One of the examples this can be used is in {@link ChannelFutureListener}.
     * The {@link ChannelFuture} can be complete when the eventloop handles the different request. The
     * eventloop might have the wrong {@link RequestContext} in the {@link RequestContextStorage},
     * so we should pop it.
     */
    @MustBeClosed
    public static SafeCloseable pop() {
        final RequestContext oldCtx = requestContextStorage.currentOrNull();
        if (oldCtx == null) {
            return noopSafeCloseable();
        }

        pop(oldCtx, null);
        return () -> requestContextStorage.push(oldCtx);
    }

    /**
     * Pops the current {@link RequestContext} in the storage and pushes back the specified {@code toRestore}.
     */
    public static void pop(RequestContext current, @Nullable RequestContext toRestore) {
        requireNonNull(current, "current");
        requestContextStorage.pop(current, toRestore);
    }

    /**
     * Invokes {@link DefaultServiceRequestContext#hook()} or {@link DefaultClientRequestContext#hook()} and
     * returns {@link SafeCloseable} which pops the current {@link RequestContext} in the storage and pushes
     * back the specified {@code toRestore}.
     */
    public static SafeCloseable invokeHookAndPop(RequestContext current, @Nullable RequestContext toRestore) {
        requireNonNull(current, "current");

        final AutoCloseable closeable = invokeHook(current);
        if (closeable == null) {
            return () -> requestContextStorage.pop(current, toRestore);
        } else {
            return () -> {
                try {
                    closeable.close();
                } catch (Throwable t) {
                    logger.warn("{} Unexpected exception while closing RequestContext.hook().", current, t);
                }
                requestContextStorage.pop(current, toRestore);
            };
        }
    }

    @Nullable
    private static AutoCloseable invokeHook(RequestContext ctx) {
        final Supplier<? extends AutoCloseable> hook;
        if (ctx instanceof DefaultServiceRequestContext) {
            hook = ((DefaultServiceRequestContext) ctx).hook();
        } else if (ctx instanceof DefaultClientRequestContext) {
            hook = ((DefaultClientRequestContext) ctx).hook();
        } else {
            hook = null;
        }

        if (hook == null) {
            return null;
        }

        final AutoCloseable closeable;
        try {
            closeable = hook.get();
        } catch (Throwable t) {
            logger.warn("Unexpected exception while executing RequestContext.hook().get(). ctx: {}", ctx, t);
            return null;
        }

        if (closeable == null) {
            logger.warn("RequestContext.hook().get() returned null. ctx: {}", ctx);
            return null;
        }

        return closeable;
    }

    private RequestContextUtil() {}
}
