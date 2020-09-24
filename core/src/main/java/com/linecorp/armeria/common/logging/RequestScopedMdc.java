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
package com.linecorp.armeria.common.logging;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.util.Exceptions;

import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Provides the access to request-scoped {@link MDC} properties. All properties set via the access methods in
 * this class are bound to a {@link RequestContext}, unlike the traditional thread-local {@link MDC} properties.
 *
 * <h2>Updating the request-scoped context map</h2>
 *
 * <p>Update the request-scoped context map using {@link #put(RequestContext, String, String)},
 * {@link #putAll(RequestContext, Map)}, {@link #remove(RequestContext, String)} and
 * {@link #clear(RequestContext)}:
 * <pre>{@code
 * RequestContext ctx = ...;
 * RequestScopedMdc.put(ctx, "transactionId", "1234");
 * RequestScopedMdc.putAll(ctx, Map.of("foo", "1", "bar", "2"));
 * }</pre>
 *
 * <h2>Transferring thread-local properties</h2>
 *
 * <p>Use {@link #copy(RequestContext, String)} or {@link #copyAll(RequestContext)} to copy some or all of
 * thread-local {@link MDC} properties to the request-scoped context map:
 * <pre>{@code
 * RequestContext ctx = ...;
 * MDC.put("transactionId", "1234");
 * RequestScopedMdc.copy(ctx, "transactionId");
 * }</pre>
 *
 * <h2>Retrieving a value from the request-scoped context map</h2>
 *
 * <p>You can explicitly retrieve request-scoped properties using {@link #get(RequestContext, String)} or
 * {@link #getAll(RequestContext)}:
 * <pre>{@code
 * RequestContext ctx = ...;
 * String transactionId = RequestScopedMdc.get(ctx, "transactionId");
 * }</pre>
 *
 * <p>{@link RequestScopedMdc} replaces SLF4J's underlying {@link MDCAdapter} implementation so that
 * {@link MDC#get(String)} and {@link MDC#getCopyOfContextMap()} look into the request-scoped context map
 * before the thread-local context map:
 * <pre>{@code
 * RequestContext ctx = ...;
 * RequestScopedMdc.put(ctx, "transactionId", "1234");
 * try (SafeCloseable ignored = ctx.push()) {
 *     assert MDC.get("transactionId").equals("1234");
 *
 *     // A request-scoped property always gets higher priority:
 *     MDC.put("transactionId", "5678");
 *     assert MDC.get("transactionId").equals("1234");
 * }
 *
 * // Now using the thread-local property
 * // because not in a request scope anymore
 * assert MDC.get("transactionId").equals("5678");
 * }</pre>
 */
public final class RequestScopedMdc {

    private static final Logger logger = LoggerFactory.getLogger(RequestScopedMdc.class);

    private static final AttributeKey<Object2ObjectMap<String, String>> MAP =
            AttributeKey.valueOf(RequestScopedMdc.class, "map");

    private static final String ERROR_MESSAGE =
            "Failed to replace the " + MDCAdapter.class.getSimpleName() + "; " +
            RequestScopedMdc.class.getSimpleName() + " will not work.";

    @Nullable
    private static final MDCAdapter delegate;

    @Nullable
    private static final MethodHandle delegateGetPropertyMap;

    static {
        // Trigger the initialization of the default MDC adapter.
        MDC.get("");

        // Replace the default MDC adapter with ours.
        MDCAdapter oldAdapter;
        try {
            final Field mdcAdapterField = MDC.class.getDeclaredField("mdcAdapter");
            mdcAdapterField.setAccessible(true);
            oldAdapter = (MDCAdapter) mdcAdapterField.get(null);
            mdcAdapterField.set(null, new Adapter(oldAdapter));
        } catch (Throwable t) {
            oldAdapter = null;
            logger.warn(ERROR_MESSAGE, t);
        }
        delegate = oldAdapter;

        MethodHandle oldAdapterGetPropertyMap = null;
        if (delegate != null) {
            try {
                oldAdapterGetPropertyMap =
                        MethodHandles.publicLookup()
                                     .findVirtual(oldAdapter.getClass(), "getPropertyMap",
                                                  MethodType.methodType(Map.class))
                                     .bindTo(delegate);
                @SuppressWarnings("unchecked")
                final Map<String, String> map =
                        (Map<String, String>) oldAdapterGetPropertyMap.invokeExact();
                logger.trace("Retrieved MDC property map via getPropertyMap(): {}", map);
                logger.debug("Using MDCAdapter.getPropertyMap()");
            } catch (Throwable t) {
                oldAdapterGetPropertyMap = null;
                logger.debug("getPropertyMap() is not available:", t);
            }
        }
        delegateGetPropertyMap = oldAdapterGetPropertyMap;
    }

    /**
     * Returns the value of the specified request-scoped {@link MDC} property bound to the specified
     * {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     * @param key the key of the request-scoped {@link MDC} property
     *
     * @return the request-scoped {@link MDC} property. {@code null} if not found.
     */
    @Nullable
    public static String get(RequestContext ctx, String key) {
        requireNonNull(ctx, "ctx");
        requireNonNull(key, "key");

        final String value = getMap(ctx).get(key);
        if (value != null) {
            return value;
        }

        final RequestContext rootCtx = ctx.root();
        if (rootCtx != null && rootCtx != ctx) {
            return getMap(rootCtx).get(key);
        }

        return null;
    }

    /**
     * Returns the {@link Map} of all request-scoped {@link MDC} properties bound to the specified
     * {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     *
     * @return the {@link Map} that contains all request-scoped {@link MDC} properties.
     *         An empty {@link Map} if there are no request-scoped {@link MDC} properties.
     */
    public static Map<String, String> getAll(RequestContext ctx) {
        requireNonNull(ctx, "ctx");

        final Object2ObjectMap<String, String> map = getMap(ctx);
        final RequestContext rootCtx = ctx.root();
        if (rootCtx == null || rootCtx == ctx) {
            return map;
        }

        final Object2ObjectMap<String, String> rootMap = getMap(rootCtx);
        if (rootMap.isEmpty()) {
            return map;
        }

        if (map.isEmpty()) {
            return rootMap;
        }

        final Object2ObjectMap<String, String> merged =
                new Object2ObjectOpenHashMap<>(rootMap.size() + map.size());
        merged.putAll(rootMap);
        merged.putAll(map);
        return merged;
    }

    /**
     * Binds the specified request-scoped {@link MDC} property to the specified {@link RequestContext}.
     *
     * @param ctx   the {@link RequestContext}
     * @param key   the key of the request-scoped {@link MDC} property
     * @param value the value of the request-scoped {@link MDC} property
     */
    public static void put(RequestContext ctx, String key, @Nullable String value) {
        requireNonNull(ctx, "ctx");
        requireNonNull(key, "key");

        synchronized (ctx) {
            final Object2ObjectMap<String, String> oldMap = getMap(ctx);
            final Object2ObjectMap<String, String> newMap;
            if (oldMap.isEmpty()) {
                newMap = Object2ObjectMaps.singleton(key, value);
            } else {
                final Object2ObjectMap<String, String> tmp =
                        new Object2ObjectOpenHashMap<>(oldMap.size() + 1);
                tmp.putAll(oldMap);
                tmp.put(key, value);
                newMap = Object2ObjectMaps.unmodifiable(tmp);
            }
            ctx.setAttr(MAP, newMap);
        }
    }

    /**
     * Binds the specified request-scoped {@link MDC} properties to the specified {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     * @param map the {@link Map} that contains the request-scoped {@link MDC} properties
     */
    public static void putAll(RequestContext ctx, Map<String, String> map) {
        requireNonNull(ctx, "ctx");
        requireNonNull(map, "map");
        if (map.isEmpty()) {
            return;
        }

        synchronized (ctx) {
            final Object2ObjectMap<String, String> oldMap = getMap(ctx);
            final Object2ObjectMap<String, String> newMap;
            if (oldMap.isEmpty()) {
                newMap = new Object2ObjectOpenHashMap<>(map);
            } else {
                newMap = new Object2ObjectOpenHashMap<>(oldMap.size() + map.size());
                newMap.putAll(oldMap);
                newMap.putAll(map);
            }
            ctx.setAttr(MAP, Object2ObjectMaps.unmodifiable(newMap));
        }
    }

    /**
     * Copies the specified thread-local {@link MDC} property to the specified {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     * @param key the key of the thread-local {@link MDC} property to copy
     */
    public static void copy(RequestContext ctx, String key) {
        requireNonNull(ctx, "ctx");
        requireNonNull(key, "key");
        checkState(delegate != null, ERROR_MESSAGE);
        put(ctx, key, delegate.get(key));
    }

    /**
     * Copies all thread-local {@link MDC} properties to the specified {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     */
    public static void copyAll(RequestContext ctx) {
        requireNonNull(ctx, "ctx");
        checkState(delegate != null, ERROR_MESSAGE);
        final Map<String, String> map = getDelegateContextMap();
        if (map != null) {
            putAll(ctx, map);
        }
    }

    @Nullable
    private static Map<String, String> getDelegateContextMap() {
        assert delegate != null;
        try {
            // Try to use `LogbackMDCAdapter.getPropertyMap()` which does not make a copy.
            @SuppressWarnings("unchecked")
            final Map<String, String> map =
                    delegateGetPropertyMap != null ? (Map<String, String>) delegateGetPropertyMap.invokeExact()
                                                   : delegate.getCopyOfContextMap();
            return map;
        } catch (Throwable t) {
            // We should not reach here because we tested `invokeExact()` works
            // in the class initializer above.
            Exceptions.throwUnsafely(t);
        }
        return null;
    }

    /**
     * Unbinds the specified request-scoped {@link MDC} property from the specified {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     * @param key the key of the request-scoped {@link MDC} property to unbind
     */
    public static void remove(RequestContext ctx, String key) {
        requireNonNull(ctx, "ctx");
        requireNonNull(key, "key");

        synchronized (ctx) {
            final Object2ObjectMap<String, String> oldMap = getMap(ctx);
            if (!oldMap.containsKey(key)) {
                return;
            }

            final Object2ObjectMap<String, String> newMap;
            if (oldMap.size() == 1) {
                newMap = Object2ObjectMaps.emptyMap();
            } else {
                final Object2ObjectOpenHashMap<String, String> tmp = new Object2ObjectOpenHashMap<>(oldMap);
                tmp.remove(key);
                newMap = Object2ObjectMaps.unmodifiable(tmp);
            }
            ctx.setAttr(MAP, newMap);
        }
    }

    /**
     * Unbinds all request-scoped {@link MDC} properties from the specified {@link RequestContext}.
     *
     * @param ctx the {@link RequestContext}
     */
    public static void clear(RequestContext ctx) {
        requireNonNull(ctx, "ctx");

        synchronized (ctx) {
            final Object2ObjectMap<String, String> oldMap = getMap(ctx);
            if (!oldMap.isEmpty()) {
                ctx.setAttr(MAP, Object2ObjectMaps.emptyMap());
            }
        }
    }

    private static Object2ObjectMap<String, String> getMap(RequestContext ctx) {
        final Object2ObjectMap<String, String> map = ctx.ownAttr(MAP);
        return firstNonNull(map, Object2ObjectMaps.emptyMap());
    }

    private RequestScopedMdc() {}

    private static final class Adapter implements MDCAdapter {

        private final MDCAdapter delegate;

        Adapter(MDCAdapter delegate) {
            this.delegate = requireNonNull(delegate, "delegate");
        }

        @Override
        @Nullable
        public String get(String key) {
            final RequestContext ctx = RequestContext.currentOrNull();
            if (ctx != null) {
                final String value = RequestScopedMdc.get(ctx, key);
                if (value != null) {
                    return value;
                }
            }

            return delegate.get(key);
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            final Map<String, String> threadLocalMap = getDelegateContextMap();
            final RequestContext ctx = RequestContext.currentOrNull();
            if (ctx == null) {
                // No context available
                if (threadLocalMap != null) {
                    return maybeCloneThreadLocalMap(threadLocalMap);
                } else {
                    return Object2ObjectMaps.emptyMap();
                }
            }

            // Retrieve the request-scoped properties.
            // Note that this map is 1) unmodifiable and shared 2) or modifiable yet unshared,
            // which means it's OK to return as it is or mutate it.
            final Map<String, String> requestScopedMap = getAll(ctx);
            if (threadLocalMap == null || threadLocalMap.isEmpty()) {
                // No thread-local map available
                return requestScopedMap;
            }

            // Thread-local map available
            if (requestScopedMap.isEmpty()) {
                // Only thread-local map available
                return maybeCloneThreadLocalMap(threadLocalMap);
            }

            // Both thread-local and request-scoped map available
            final Object2ObjectOpenHashMap<String, String> merged;
            if (requestScopedMap instanceof Object2ObjectOpenHashMap) {
                // Reuse the mutable copy returned by getAll() for less memory footprint.
                merged = (Object2ObjectOpenHashMap<String, String>) requestScopedMap;
                threadLocalMap.forEach(merged::putIfAbsent);
            } else {
                merged = new Object2ObjectOpenHashMap<>(threadLocalMap.size() + requestScopedMap.size());
                merged.putAll(threadLocalMap);
                merged.putAll(requestScopedMap);
            }
            return merged;
        }

        private static Map<String, String> maybeCloneThreadLocalMap(Map<String, String> threadLocalMap) {
            // Copy only when we retrieved the thread local map from `getPropertyMap()`.
            return delegateGetPropertyMap != null ? new Object2ObjectOpenHashMap<>(threadLocalMap)
                                                  : threadLocalMap;
        }

        @Override
        public void put(String key, @Nullable String val) {
            delegate.put(key, val);
        }

        @Override
        public void remove(String key) {
            delegate.remove(key);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public void setContextMap(Map<String, String> contextMap) {
            delegate.setContextMap(contextMap);
        }
    }
}
