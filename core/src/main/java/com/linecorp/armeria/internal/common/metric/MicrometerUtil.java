/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.internal.common.metric;

import static java.util.Objects.requireNonNull;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

import com.google.common.collect.MapMaker;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.metric.MeterIdPrefix;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * A utility that prevents double instantiation of an object for a certain {@link MeterIdPrefix}. This can be
 * useful when you need to make sure you do not register the same {@link Meter} more than once.
 *
 * @see RequestMetricSupport
 * @see CaffeineMetricSupport
 */
public final class MicrometerUtil {

    private static final ConcurrentMap<MeterRegistry, ConcurrentMap<MeterIdPrefix, Object>> map =
            new MapMaker().weakKeys().makeMap();
    private static final ThreadLocal<RegistrationState> registrationState =
            ThreadLocal.withInitial(RegistrationState::new);

    /**
     * Associates a newly-created object with the specified {@link MeterIdPrefix} or returns an existing one if
     * exists already.
     *
     * @param idPrefix the {@link MeterIdPrefix} of the object
     * @param type the type of the object created by {@code factory}
     * @param factory a factory that creates an object of {@code type}, to be associated with {@code idPrefix}
     *
     * @return the object of {@code type}, which may or may not be created by {@code factory}
     * @throws IllegalStateException if there is already an object of different class associated
     *                               for the same {@link MeterIdPrefix}
     */
    public static <T> T register(MeterRegistry registry, MeterIdPrefix idPrefix, Class<T> type,
                                 BiFunction<MeterRegistry, MeterIdPrefix, T> factory) {
        requireNonNull(registry, "registry");
        requireNonNull(idPrefix, "idPrefix");
        requireNonNull(type, "type");
        requireNonNull(factory, "factory");

        final ConcurrentMap<MeterIdPrefix, Object> objects =
                map.computeIfAbsent(registry, unused -> new ConcurrentHashMap<>());

        // Prevent calling computeIfAbsent inside computeIfAbsent.
        // See https://bugs.openjdk.java.net/browse/JDK-8062841 for more information.
        final RegistrationState registrationState = MicrometerUtil.registrationState.get();
        if (registrationState.isRegistering) {
            throw new IllegalStateException("nested registration prohibited");
        }

        final Object object = register(objects, registrationState, registry, idPrefix, type, factory);

        // Handle the registerLater() calls, if any were made by the factory.
        handlePendingRegistrations(objects, registrationState);

        @SuppressWarnings("unchecked")
        final T cast = (T) object;
        return cast;
    }

    private static <T> Object register(ConcurrentMap<MeterIdPrefix, Object> map,
                                       RegistrationState registrationState,
                                       MeterRegistry registry, MeterIdPrefix idPrefix, Class<T> type,
                                       BiFunction<MeterRegistry, MeterIdPrefix, T> factory) {
        @Nullable
        final Object object = map.computeIfAbsent(idPrefix, i -> {
            registrationState.isRegistering = true;
            try {
                return factory.apply(registry, i);
            } finally {
                registrationState.isRegistering = false;
            }
        });

        if (!type.isInstance(object)) {
            throw new IllegalStateException(
                    "An object of different type has been registered already for idPrefix: " + idPrefix +
                    " (expected: " + type.getName() +
                    ", actual: " + (object != null ? object.getClass().getName() : "null") + ')');
        }
        return object;
    }

    private static void handlePendingRegistrations(ConcurrentMap<MeterIdPrefix, Object> map,
                                                   RegistrationState registrationState) {
        for (;;) {
            @SuppressWarnings("unchecked")
            @Nullable
            final PendingRegistration<Object> pendingRegistration =
                    (PendingRegistration<Object>) registrationState.pendingRegistrations.poll();

            if (pendingRegistration == null) {
                break;
            }

            register(map, registrationState, pendingRegistration.registry, pendingRegistration.idPrefix,
                     pendingRegistration.type, pendingRegistration.factory
            );
        }
    }

    /**
     * Similar to {@link #register(MeterRegistry, MeterIdPrefix, Class, BiFunction)}, but used when
     * a registration has to be nested, because otherwise the registration may enter an infinite loop,
     * as described <a href="https://bugs.openjdk.java.net/browse/JDK-8062841">here</a>. For example:
     * <pre>{@code
     * // OK
     * register(registry, idPrefix, type, (r, i) -> {
     *     registerLater(registry, anotherIdPrefix, anotherType, ...);
     *     return ...;
     * });
     *
     * // Not OK
     * register(registry, idPrefix, type, (r, i) -> {
     *     register(registry, anotherIdPrefix, anotherType, ...);
     *     return ...;
     * });
     * }</pre>
     */
    public static <T> void registerLater(MeterRegistry registry, MeterIdPrefix idPrefix, Class<T> type,
                                         BiFunction<MeterRegistry, MeterIdPrefix, T> factory) {

        final RegistrationState registrationState = MicrometerUtil.registrationState.get();
        if (!registrationState.isRegistering) {
            register(registry, idPrefix, type, factory);
        } else {
            registrationState.pendingRegistrations.add(
                    new PendingRegistration<>(registry, idPrefix, type, factory));
        }
    }

    private MicrometerUtil() {}

    private static final class RegistrationState {
        boolean isRegistering;
        final Queue<PendingRegistration<?>> pendingRegistrations = new ArrayDeque<>();
    }

    private static final class PendingRegistration<T> {
        final MeterRegistry registry;
        final MeterIdPrefix idPrefix;
        final Class<T> type;
        final BiFunction<MeterRegistry, MeterIdPrefix, T> factory;

        PendingRegistration(MeterRegistry registry, MeterIdPrefix idPrefix, Class<T> type,
                            BiFunction<MeterRegistry, MeterIdPrefix, T> factory) {
            this.registry = registry;
            this.idPrefix = idPrefix;
            this.type = type;
            this.factory = factory;
        }
    }
}
