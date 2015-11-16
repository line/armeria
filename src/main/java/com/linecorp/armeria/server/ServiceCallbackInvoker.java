/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * A helper class that invokes the callback methods in {@link Service}, {@link ServiceCodec} and
 * {@link ServiceInvocationHandler}. It ensures callback methods are invoked only once for the same
 * {@link Server} instance.
 */
public final class ServiceCallbackInvoker {

    /**
     * NOTE: Used weak-key just in case a service component is from a different {@link ClassLoader}
     *       because otherwise the keys will be leaked in a container environment where apps can be reloaded.
     */
    private static final WeakIdentityMap<Object, Set<Server>> map = new WeakIdentityMap<>();

    /**
     * Invokes {@link Service#serviceAdded(Server)}.
     */
    public static void invokeServiceAdded(Server server, Service service) {
        requireNonNull(server, "server");
        requireNonNull(service, "service");

        final Set<Server> owners = map.getOrCompute(service, HashSet<Server>::new);
        if (owners.contains(server)) {
            // Invoked already
            return;
        }

        try {
            service.serviceAdded(server);
        } catch (Exception e) {
            fail("serviceAdded", service, e);
        }

        owners.add(server);
    }

    /**
     * Invokes {@link ServiceCodec#codecAdded(Server)}.
     */
    public static void invokeCodecAdded(Server server, ServiceCodec codec) {
        requireNonNull(server, "server");
        requireNonNull(codec, "codec");

        final Set<Server> owners = map.getOrCompute(codec, HashSet<Server>::new);
        if (owners.contains(server)) {
            // Invoked already
            return;
        }

        try {
            codec.codecAdded(server);
        } catch (Exception e) {
            fail("codecAdded", codec, e);
        }

        owners.add(server);
    }

    /**
     * Invokes {@link ServiceInvocationHandler#handlerAdded(Server)}.
     */
    public static void invokeHandlerAdded(Server server, ServiceInvocationHandler handler) {
        requireNonNull(server, "server");
        requireNonNull(handler, "handler");

        final Set<Server> owners = map.getOrCompute(handler, HashSet<Server>::new);
        if (owners.contains(server)) {
            // Invoked already
            return;
        }

        try {
            handler.handlerAdded(server);
        } catch (Exception e) {
            fail("handlerAdded", handler, e);
        }

        owners.add(server);
    }

    private static <T> void fail(String operationName, T component, Exception e) {
        throw new IllegalStateException(
                "failed to invoke " + operationName + "() on: " + component, e);
    }

    private static class WeakIdentityMap<K, V> {
        private final Map<KeyRef<K>, V> map = new HashMap<>();
        private final ReferenceQueue<K> refQueue = new ReferenceQueue<>();

        synchronized V getOrCompute(K key, Supplier<V> supplier) {
            V value = get(key);
            if (value == null) {
                value = supplier.get();
                assert value != null;
                put(key, value);
            }

            return value;
        }

        private V get(K key) {
            expunge();
            return map.get(new KeyRef<>(key));
        }

        private V put(K key, V value) {
            expunge();
            requireNonNull(key, "key");
            requireNonNull(value, "value");

            return map.put(new KeyRef<K>(key, refQueue), value);
        }

        private void expunge() {
            Reference<? extends K> ref;
            while ((ref = refQueue.poll()) != null) {
                map.remove(ref);
            }
        }
    }

    private static final class KeyRef<T> extends WeakReference<T> {

        private final int hashCode;

        KeyRef(T key) {
            this(key, null);
        }

        KeyRef(T key, ReferenceQueue<T> q) {
            super(key, q);
            requireNonNull(key, "key");
            hashCode = key.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof KeyRef)) {
                return false;
            }

            final KeyRef<?> that = (KeyRef<?>) o;
            final Object value = get();
            return value != null && value == that.get();
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public String toString() {
            return String.valueOf(get());
        }
    }

    private ServiceCallbackInvoker() {}
}
