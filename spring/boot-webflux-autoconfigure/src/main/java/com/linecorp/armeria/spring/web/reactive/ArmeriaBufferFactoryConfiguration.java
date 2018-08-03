/*
 * Copyright 2018 LINE Corporation
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
package com.linecorp.armeria.spring.web.reactive;

import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;

import com.linecorp.armeria.spring.web.ArmeriaWebServer;

/**
 * A configuration class which creates an {@link ArmeriaBufferFactory}.
 */
@Configuration
public class ArmeriaBufferFactoryConfiguration {
    /**
     * Returns a new {@link DataBufferFactory} for {@link ArmeriaWebServer} and
     * {@link ArmeriaClientHttpConnector}.
     */
    @Bean
    @ConditionalOnMissingBean(ArmeriaBufferFactory.class)
    public ArmeriaBufferFactoryHolder armeriaBufferFactoryHolder(
            Optional<DataBufferFactory> dataBufferFactory) {
        if (dataBufferFactory.isPresent()) {
            return new ArmeriaBufferFactoryHolder(ArmeriaBufferFactory.of(dataBufferFactory.get()));
        }
        return new ArmeriaBufferFactoryHolder(ArmeriaBufferFactory.DEFAULT);
    }

    /**
     * An {@link ArmeriaBufferFactory} holder class. Because the {@link ArmeriaBufferFactory} is a subclass
     * of the {@link DataBufferFactory}, there can be a conflict when resolving beans, if there are two
     * {@link DataBufferFactory} beans which are a {@link DataBufferFactory} and an {@link ArmeriaBufferFactory}
     * respectively. This holder bean avoids the conflict by wrapping the {@link ArmeriaBufferFactory} instance.
     */
    static class ArmeriaBufferFactoryHolder implements Supplier<ArmeriaBufferFactory> {
        private final ArmeriaBufferFactory factory;

        ArmeriaBufferFactoryHolder(ArmeriaBufferFactory factory) {
            this.factory = factory;
        }

        @Override
        public ArmeriaBufferFactory get() {
            return factory;
        }
    }
}
