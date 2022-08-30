package com.linecorp.armeria.server.graphql;

import java.util.List;
import java.util.function.Consumer;

import org.dataloader.DataLoaderRegistry;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * TBD.
 */
@FunctionalInterface
public interface DataLoaderRegistryStrategy {

    /**
     * TBD.
     */
    static DataLoaderRegistryStrategy ofFixed() {
        return ofFixed(ImmutableList.of());
    }

    /**
     * TBD.
     */
    static DataLoaderRegistryStrategy ofFixed(List<Consumer<? super DataLoaderRegistry>> consumers) {
        return new FixedDataLoaderRegistryStrategy(ImmutableList.copyOf(consumers));
    }

    /**
     * TBD.
     */
    static DataLoaderRegistryStrategy ofEach() {
        return ofEach(ImmutableList.of());
    }

    /**
     * TBD.
     */
    static DataLoaderRegistryStrategy ofEach(List<Consumer<? super DataLoaderRegistry>> consumers) {
        return new EachDataLoaderRegistryStrategy(ImmutableList.copyOf(consumers));
    }

    /**
     * TBD.
     */
    DataLoaderRegistry apply(ServiceRequestContext context);

    /**
     * TBD.
     */
    class FixedDataLoaderRegistryStrategy implements DataLoaderRegistryStrategy {

        private final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();

        FixedDataLoaderRegistryStrategy(List<Consumer<? super DataLoaderRegistry>> consumers) {
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
     * TBD.
     */
    class EachDataLoaderRegistryStrategy implements DataLoaderRegistryStrategy {

        private final List<Consumer<? super DataLoaderRegistry>> consumers;

        EachDataLoaderRegistryStrategy(List<Consumer<? super DataLoaderRegistry>> consumers) {
            this.consumers = consumers;
        }

        @Override
        public DataLoaderRegistry apply(ServiceRequestContext context) {
            final DataLoaderRegistry dataLoaderRegistry = new DataLoaderRegistry();
            for (Consumer<? super DataLoaderRegistry> configurer : consumers) {
                configurer.accept(dataLoaderRegistry);
            }
            return dataLoaderRegistry;
        }
    }
}
