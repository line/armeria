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

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.MapMaker;
import com.google.common.collect.Streams;

import com.linecorp.armeria.common.ContextStorage;
import com.linecorp.armeria.common.ContextStorageProvider;
import com.linecorp.armeria.common.ContextStorageThreadLocal;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.SafeCloseable;

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

    private static final ContextStorage contextStorage;

    static {
        final List<ContextStorageProvider> providers = Streams.stream(
                ServiceLoader.load(ContextStorageProvider.class)).collect(toImmutableList());

        if (providers.isEmpty()) {
            contextStorage = new ContextStorageThreadLocal();
        } else {
            final ContextStorageProvider provider = providers.get(0);
            if (providers.size() > 1) {
                logger.warn("Found more than one {}. Only the first provider is used: {}, providers: {}",
                            ContextStorageProvider.class.getSimpleName(), provider, providers);
            }
            ContextStorage temp;
            try {
                temp = provider.newContextStorage();
            } catch (Throwable t) {
                logger.warn("Failed to create context storage. provider: {}", provider, t);
                temp = new ContextStorageThreadLocal();
            }
            contextStorage = temp;
        }
    }

    /**
     * Returns the {@link SafeCloseable} which doesn't do anything.
     */
    public static SafeCloseable noopSafeCloseable() {
        return noopSafeCloseable;
    }

    /**
     * Returns the {@link ContextStorage}.
     */
    public static ContextStorage storage() {
        return contextStorage;
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
     * Removes the {@link RequestContext} in the thread-local if exists and returns {@link SafeCloseable} which
     * pushes the {@link RequestContext} back to the thread-local.
     *
     * <p>Because this method pops the {@link RequestContext} arbitrarily, it shouldn't be used in
     * most cases. One of the examples this can be used is in {@link ChannelFutureListener}.
     * The {@link ChannelFuture} can be complete when the eventloop handles the different request. The
     * eventloop might have the wrong {@link RequestContext} in the thread-local, so we should pop it.
     */
    public static SafeCloseable pop() {
        final ContextStorage contextStorage = storage();
        final RequestContext oldCtx = contextStorage.currentOrNull();
        if (oldCtx == null) {
            return noopSafeCloseable();
        }

        contextStorage.pop(null);
        return () -> contextStorage.push(oldCtx);
    }

    private RequestContextUtil() {}
}
