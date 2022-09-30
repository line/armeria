/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.server.graphql;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.dataloader.DataLoader;
import org.dataloader.DataLoaderRegistry;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * {@link DataLoaderRegistry} creation strategy that creates a {@link DataLoader}.
 */
@UnstableApi
@FunctionalInterface
public interface DataLoaderRegistryCreationStrategy {

    /**
     * Returns a strategy that creates a fixed {@link DataLoaderRegistry}.
     * Note: Using a fixed {@link DataLoaderRegistry} is not recommended. Instead, use a
     *       {@link DataLoaderRegistry} that is created per request.
     */
    static DataLoaderRegistryCreationStrategy ofFixed() {
        return FixedDataLoaderRegistryCreationStrategy.INSTANCE;
    }

    /**
     * Returns a strategy that creates a fixed {@link DataLoaderRegistry}.
     * Note: Using a fixed {@link DataLoaderRegistry} is not recommended. Instead, use a
     *       {@link DataLoaderRegistry} that is created per request.
     */
    static DataLoaderRegistryCreationStrategy ofFixed(List<Consumer<? super DataLoaderRegistry>> consumers) {
        requireNonNull(consumers, "consumers");
        return new FixedDataLoaderRegistryCreationStrategy(ImmutableList.copyOf(consumers));
    }

    /**
     * Returns a strategy that creates a {@link DataLoaderRegistry} per request.
     */
    static DataLoaderRegistryCreationStrategy of() {
        return DefaultDataLoaderRegistryStrategy.INSTANCE;
    }

    /**
     * Returns a strategy that creates a {@link DataLoaderRegistry} per request.
     */
    static DataLoaderRegistryCreationStrategy of(
            List<BiConsumer<? super DataLoaderRegistry, ? super ServiceRequestContext>> consumers) {
        requireNonNull(consumers, "consumers");
        return new DefaultDataLoaderRegistryStrategy(ImmutableList.copyOf(consumers));
    }

    /**
     * Applies to create the {@link DataLoaderRegistry}.
     */
    DataLoaderRegistry apply(ServiceRequestContext context);

    /**
     * This class is a strategy for creating a fixed {@link DataLoaderRegistry}.
     */
    class FixedDataLoaderRegistryCreationStrategy implements DataLoaderRegistryCreationStrategy {

        private static final DataLoaderRegistryCreationStrategy INSTANCE =
                new FixedDataLoaderRegistryCreationStrategy(ImmutableList.of());

        private final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();

        FixedDataLoaderRegistryCreationStrategy(List<Consumer<? super DataLoaderRegistry>> consumers) {
            requireNonNull(consumers, "consumers");
            for (Consumer<? super DataLoaderRegistry> configurer : consumers) {
                configurer.accept(dataLoaderRegistry);
            }
        }

        @Override
        public DataLoaderRegistry apply(ServiceRequestContext context) {
            return dataLoaderRegistry;
        }
    }

    /**
     * This class is a strategy for creating a {@link DataLoaderRegistry} per request.
     */
    class DefaultDataLoaderRegistryStrategy implements DataLoaderRegistryCreationStrategy {

        private static final DataLoaderRegistryCreationStrategy INSTANCE =
                new DefaultDataLoaderRegistryStrategy(ImmutableList.of());

        private final List<BiConsumer<? super DataLoaderRegistry, ? super ServiceRequestContext>> consumers;

        DefaultDataLoaderRegistryStrategy(
                List<BiConsumer<? super DataLoaderRegistry, ? super ServiceRequestContext>> consumers) {
            this.consumers = requireNonNull(consumers, "consumers");
        }

        @Override
        public DataLoaderRegistry apply(ServiceRequestContext context) {
            final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
            for (BiConsumer<? super DataLoaderRegistry, ? super ServiceRequestContext> consumer : consumers) {
                consumer.accept(dataLoaderRegistry, context);
            }
            return dataLoaderRegistry;
        }
    }
}
