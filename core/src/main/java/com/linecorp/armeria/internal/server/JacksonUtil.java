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
 * under the License
 */

package com.linecorp.armeria.internal.server;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.JacksonObjectMapperProvider;

public final class JacksonUtil {

    private static final Logger logger = LoggerFactory.getLogger(JacksonUtil.class);

    private static final List<JacksonObjectMapperProvider> providers =
            ImmutableList.copyOf(ServiceLoader.load(JacksonObjectMapperProvider.class));

    private static boolean noticed;

    private static final ObjectMapper INSTANCE = newDefaultObjectMapper();

    public static ObjectMapper newDefaultObjectMapper() {
        if (providers.isEmpty()) {
            // Create the default ObjectMapper with the modules provided by SPI.
            final JsonMapper.Builder jsonMapperBuilder = JsonMapper.builder();
            jsonMapperBuilder.findAndAddModules();
            final ObjectMapper mapper = jsonMapperBuilder.build();
            final Set<Object> registeredModuleIds = mapper.getRegisteredModuleIds();
            for (Object registeredModuleId : registeredModuleIds) {
                if ("com.fasterxml.jackson.module.scala.DefaultScalaModule".equals(registeredModuleId)) {
                    // Disallow a null value for non-Option fields. Option[A] is commonly preferred.
                    mapper.enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
                }
            }
            if (!noticed) {
                logger.debug("Available Jackson Modules: {}", registeredModuleIds);
                noticed = true;
            }
            return mapper;
        }

        // Use a custom ObjectMapper provided via SPI.
        final JacksonObjectMapperProvider provider = providers.get(0);
        if (!noticed) {
            if (providers.size() > 1) {
                logger.warn("Found more than one {}. The first provider found is used among {}",
                            JacksonObjectMapperProvider.class.getSimpleName(), providers);
            } else {
                logger.info("Using {} as a {}",
                            provider.getClass().getSimpleName(),
                            JacksonObjectMapperProvider.class.getSimpleName());
            }
            noticed = true;
        }
        final ObjectMapper mapper = provider.newObjectMapper();
        requireNonNull(mapper, "provider.modules() returned null");
        return mapper;
    }

    public static byte[] writeValueAsBytes(Object value) throws JsonProcessingException {
        return INSTANCE.writeValueAsBytes(value);
    }

    private JacksonUtil() {}
}
