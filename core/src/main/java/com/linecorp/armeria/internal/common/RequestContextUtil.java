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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.ContextStorage;
import com.linecorp.armeria.common.ContextStorageProvider;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.concurrent.FastThreadLocal;

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

    /**
     * The default {@link ContextStorage} which stores the {@link RequestContext} in {@link FastThreadLocal}.
     */
    public static final ContextStorage defaultContextStorage = new ThreadLocalContextStorage();

    private static final ContextStorage contextStorage;

    static {
        final List<ContextStorageProvider> providers = ImmutableList.copyOf(
                ServiceLoader.load(ContextStorageProvider.class));
        final String contextStorageFqcn = Flags.contextStorage();
        if (!providers.isEmpty()) {
            if (providers.size() > 1) {
                throw new IllegalStateException("Found more than one " +
                                                ContextStorageProvider.class.getSimpleName() + ". providers:" +
                                                providers);
            }

            final ContextStorageProvider provider = providers.get(0);
            if (!contextStorageFqcn.isEmpty()) {
                throw new IllegalStateException("Found " + provider + " and " + contextStorageFqcn +
                                                ". Which one do you want to use?");
            }

            try {
                contextStorage = provider.newContextStorage();
            } catch (Throwable t) {
                throw new IllegalStateException("Failed to create context storage. provider: " + provider, t);
            }
        } else {
            if (contextStorageFqcn.isEmpty()) {
                contextStorage = defaultContextStorage;
            } else {
                try {
                    final Class<?> clazz = Class.forName(contextStorageFqcn);
                    contextStorage = clazz.asSubclass(ContextStorage.class)
                                          .getConstructor()
                                          .newInstance();
                } catch (Throwable t) {
                    throw new IllegalStateException("Failed to create context storage from FQCN: " +
                                                    contextStorageFqcn, t);
                }
            }
        }
    }

    /**
     * Invoked to initialize this class earlier than when an {@link HttpRequest} is received or sent.
     */
    public static void init() { /* no-op */ }

    /**
     * Returns the {@link SafeCloseable} which doesn't do anything.
     */
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

    /**
     * Returns the current {@link RequestContext} in the {@link ContextStorage}.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends RequestContext> T get() {
        return (T) contextStorage.currentOrNull();
    }

    /**
     * Sets the specified {@link RequestContext} in the {@link ContextStorage} and
     * returns the old {@link RequestContext}.
     */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends RequestContext> T getAndSet(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        return (T) contextStorage.push(ctx);
    }

    /**
     * Sets the specified {@link RequestContext} in the {@link ContextStorage}.
     */
    public static void set(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        contextStorage.push(ctx);
    }

    /**
     * Removes the {@link RequestContext} in the {@link ContextStorage} if exists and returns
     * {@link SafeCloseable} which pushes the {@link RequestContext} back to the {@link ContextStorage}.
     *
     * <p>Because this method pops the {@link RequestContext} arbitrarily, it shouldn't be used in
     * most cases. One of the examples this can be used is in {@link ChannelFutureListener}.
     * The {@link ChannelFuture} can be complete when the eventloop handles the different request. The
     * eventloop might have the wrong {@link RequestContext} in the {@link ContextStorage}, so we should pop it.
     */
    public static SafeCloseable pop() {
        final RequestContext oldCtx = contextStorage.currentOrNull();
        if (oldCtx == null) {
            return noopSafeCloseable();
        }

        pop(oldCtx, null);
        return () -> contextStorage.push(oldCtx);
    }

    public static void pop(RequestContext current, @Nullable RequestContext toRestore) {
        requireNonNull(current, "current");
        contextStorage.pop(current, toRestore);
    }

    private RequestContextUtil() {}
}
