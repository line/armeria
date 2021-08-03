/*
 * Copyright 2021 LINE Corporation
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

import java.util.List;
import java.util.ServiceLoader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.JacksonObjectMapperProvider;

public final class JacksonUtil {

    private static final Logger logger = LoggerFactory.getLogger(JacksonUtil.class);

    private static final JacksonObjectMapperProvider provider;

    static {
        final List<JacksonObjectMapperProvider> providers =
                ImmutableList.copyOf(ServiceLoader.load(JacksonObjectMapperProvider.class));
        if (!providers.isEmpty()) {
            // Use a custom ObjectMapper provided via SPI.
            provider = providers.get(0);
            if (providers.size() > 1) {
                logger.warn("Found {} {}s. The first provider found will be used among {}",
                            providers.size(), JacksonObjectMapperProvider.class.getSimpleName(), providers);
            } else {
                logger.info("Using {} as a {}",
                            provider.getClass().getSimpleName(),
                            JacksonObjectMapperProvider.class.getSimpleName());
            }
        } else {
            provider = DefaultJacksonObjectMapperProvider.INSTANCE;
        }
    }

    private static final ObjectMapper INSTANCE = newDefaultObjectMapper();

    /**
     * Returns a newly-created {@link ObjectMapper} that is created by
     * either a {@link JacksonObjectMapperProvider} loaded via SPI or
     * the {@link DefaultJacksonObjectMapperProvider} if not specified.
     */
    public static ObjectMapper newDefaultObjectMapper() {
        final ObjectMapper mapper = provider.newObjectMapper();
        if (mapper == null) {
            throw new NullPointerException(
                    provider.getClass().getSimpleName() + ".newObjectMapper() returned null");
        }
        return mapper;
    }

    public static byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return INSTANCE.writeValueAsBytes(value);
    }

    private JacksonUtil() {}
}
