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

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.MDCAdapter;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.RequestContext;

import io.netty.util.AttributeKey;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;

/**
 * Provides the access to request-scoped {@link MDC} properties. All properties set via the access methods in
 * this class are bound to a {@link RequestContext}, unlike the traditional thread-local {@link MDC} properties.
 *
 * <h3>Updating the request-scoped context map</h3>
 *
 * <p>Update the request-scoped context map using {@link #put(RequestContext, String, String)},
 * {@link #putAll(RequestContext, Map)}, {@link #remove(RequestContext, String)} and
 * {@link #clear(RequestContext)}:
 * <pre>{@code
 * RequestContext ctx = ...;
 * RequestScopedMdc.put(ctx, "transactionId", "1234");
 * RequestScopedMdc.putAll(ctx, Map.of("foo", "1", "bar", "2"));
 * }</pre></p>
 *
 * <h3>Transferring thread-local properties</h3>
 *
 * <p>Use {@link #copy(RequestContext, String)} or {@link #copyAll(RequestContext)} to copy some or all of
 * thread-local {@link MDC} properties to the request-scoped context map:
 * <pre>{@code
 * RequestContext ctx = ...;
 * MDC.put("transactionId", "1234");
 * RequestScopedMdc.copy(ctx, "transactionId");
 * }</pre></p>
 *
 * <h3>Retrieving a value from the request-scoped context map</h3>
 *
 * <p>You can explicitly retrieve request-scoped properties using {@link #get(RequestContext, String)} or
 * {@link #getAll(RequestContext)}:
 * <pre>{@code
 * RequestContext ctx = ...;
 * String transactionId = RequestScopedMdc.get(ctx, "transactionId");
 * }</pre></p>
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
 * }</pre></p>
 */
public final class RequestScopedMdc {

    private static final Logger logger = LoggerFactory.getLogger(RequestScopedMdc.class);

    private static final AttributeKey<Map<String, String>> MAP =
            AttributeKey.valueOf(RequestScopedMdc.class, "map");

    private static final String ERROR_MESSAGE =
            "Failed to replace the " + MDCAdapter.class.getSimpleName() + "; " +
            RequestScopedMdc.class.getSimpleName() + " will not work.";

    @Nullable
    private static final MDCAdapter delegate;

    static {
        // Trigger the initialization of the default MDC adapter.
        MDC.get("");

        // Replace the default MDC adapter with ours.
        MDCAdapter oldAdapter = null;
        try {
            final Field mdcAdapterField = MDC.class.getDeclaredField("mdcAdapter");
            mdcAdapterField.setAccessible(true);
            oldAdapter = (MDCAdapter) mdcAdapterField.get(null);
            mdcAdapterField.set(null, new Adapter(oldAdapter));
        } catch (Throwable t) {
            logger.warn(ERROR_MESSAGE, t);
        }
        delegate = oldAdapter;
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
        return getMap(ctx).get(key);
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
        return getMap(ctx);
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
            final Map<String, String> oldMap = getMap(ctx);
            final Map<String, String> newMap;
            if (oldMap.isEmpty()) {
                newMap = Collections.singletonMap(key, value);
            } else {
                final Object2ObjectOpenHashMap<String, String> tmp =
                        new Object2ObjectOpenHashMap<>(oldMap.size() + 1);
                tmp.putAll(oldMap);
                tmp.put(key, value);
                newMap = Collections.unmodifiableMap(tmp);
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
            final Map<String, String> oldMap = getMap(ctx);
            final Map<String, String> newMap;
            if (oldMap.isEmpty()) {
                newMap = new Object2ObjectOpenHashMap<>(map);
            } else {
                newMap = new Object2ObjectOpenHashMap<>(oldMap.size() + map.size());
                newMap.putAll(oldMap);
                newMap.putAll(map);
            }
            ctx.setAttr(MAP, Collections.unmodifiableMap(newMap));
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

        final Map<String, String> map = delegate.getCopyOfContextMap();
        if (map == null || map.isEmpty()) {
            return;
        }

        synchronized (ctx) {
            final Map<String, String> oldMap = getMap(ctx);
            final Map<String, String> newMap;
            if (oldMap.isEmpty()) {
                newMap = map;
            } else {
                newMap = new Object2ObjectOpenHashMap<>(oldMap.size() + map.size());
                newMap.putAll(oldMap);
                newMap.putAll(map);
            }
            ctx.setAttr(MAP, Collections.unmodifiableMap(newMap));
        }
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
            final Map<String, String> oldMap = getMap(ctx);
            if (!oldMap.containsKey(key)) {
                return;
            }

            final Map<String, String> newMap;
            if (oldMap.size() == 1) {
                newMap = Collections.emptyMap();
            } else {
                final Object2ObjectOpenHashMap<String, String> tmp = new Object2ObjectOpenHashMap<>(oldMap);
                tmp.remove(key);
                newMap = Collections.unmodifiableMap(tmp);
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
            final Map<String, String> oldMap = getMap(ctx);
            if (!oldMap.isEmpty()) {
                ctx.setAttr(MAP, Collections.emptyMap());
            }
        }
    }

    private static Map<String, String> getMap(RequestContext ctx) {
        final Map<String, String> map;
        if (ctx instanceof ClientRequestContext) {
            map = ((ClientRequestContext) ctx).ownAttr(MAP);
        } else {
            map = ctx.attr(MAP);
        }
        return firstNonNull(map, Collections.emptyMap());
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
                final String value = getMap(ctx).get(key);
                if (value != null) {
                    return value;
                }
            }

            return delegate.get(key);
        }

        @Override
        public Map<String, String> getCopyOfContextMap() {
            final Map<String, String> threadLocalMap =
                    firstNonNull(delegate.getCopyOfContextMap(), Collections.emptyMap());
            final RequestContext ctx = RequestContext.currentOrNull();
            if (ctx == null) {
                // No context available
                return threadLocalMap;
            }

            final Map<String, String> requestScopedMap =
                    firstNonNull(getMap(ctx), Collections.emptyMap());
            if (threadLocalMap.isEmpty()) {
                // No thread-local map available
                return requestScopedMap;
            }

            // Thread-local map available
            if (requestScopedMap.isEmpty()) {
                // Only thread-local map available
                return threadLocalMap;
            }

            // Both thread-local and request-scoped map available
            final Map<String, String> merged =
                    new Object2ObjectOpenHashMap<>(threadLocalMap.size() + requestScopedMap.size());
            merged.putAll(threadLocalMap);
            merged.putAll(requestScopedMap);
            return merged;
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
