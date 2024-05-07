/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.concurrent.Executors;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

/**
 * A fluent builder to build an instance of {@link ThriftCallService}.
 *
 * @see ThriftCallService
 */
public final class ThriftCallServiceBuilder {
    private final ImmutableMap.Builder<String, Iterable<?>> implementations = ImmutableMap.builder();
    private boolean useBlockingTaskExecutor;

    ThriftCallServiceBuilder() {}

    /**
     * Adds multiple implementations for {@link ThriftServiceEntry}.
     */
    public ThriftCallServiceBuilder implementations(Map<String, ? extends Iterable<?>> implementations) {
        requireNonNull(implementations, "implementations");
        if (implementations.isEmpty()) {
            throw new IllegalArgumentException("empty implementations");
        }
        this.implementations.putAll(implementations);
        return this;
    }

    /**
     * Adds an implementation for {@link ThriftServiceEntry}.
     */
    public ThriftCallServiceBuilder addService(Object implementation) {
        requireNonNull(implementation, "implementation");
        return addService("", implementation);
    }

    /**
     * Adds an implementation with key for {@link ThriftServiceEntry}.
     */
    public ThriftCallServiceBuilder addService(String key, Object implementation) {
        requireNonNull(implementation, "implementation");
        this.implementations.put(key, ImmutableList.of(implementation));
        return this;
    }

    /**
     * Sets whether the service executes service methods using the blocking executor. By default, service
     * methods are executed directly on the event loop for implementing fully asynchronous services. If your
     * service uses blocking logic, you should either execute such logic in a separate thread using something
     * like {@link Executors#newCachedThreadPool()} or enable this setting.
     */
    public ThriftCallServiceBuilder useBlockingTaskExecutor(boolean useBlockingTaskExecutor) {
        this.useBlockingTaskExecutor = useBlockingTaskExecutor;
        return this;
    }

    /**
     * Builds a new instance of {@link ThriftCallService}.
     */
    public ThriftCallService build() {
        return new ThriftCallService(
                implementations.build().entrySet().stream().collect(
                        toImmutableMap(Map.Entry::getKey, ThriftServiceEntry::new)),
                useBlockingTaskExecutor
        );
    }
}
