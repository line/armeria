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

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBufferFactory;

/**
 * A configuration class which creates an {@link DataBufferFactoryWrapper}.
 */
@Configuration
public class DataBufferFactoryWrapperConfiguration {
    /**
     * Returns a new {@link DataBufferFactoryWrapper} for {@link ArmeriaWebServer} and
     * {@link ArmeriaClientHttpConnector}.
     */
    @Bean
    @ConditionalOnMissingBean(DataBufferFactoryWrapper.class)
    public DataBufferFactoryWrapper<?> armeriaBufferFactory(
            Optional<DataBufferFactory> dataBufferFactory) {
        if (dataBufferFactory.isPresent()) {
            return new DataBufferFactoryWrapper<>(dataBufferFactory.get());
        }
        return DataBufferFactoryWrapper.DEFAULT;
    }
}
