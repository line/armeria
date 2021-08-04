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

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import com.linecorp.armeria.common.JacksonObjectMapperProvider;

enum DefaultJacksonObjectMapperProvider implements JacksonObjectMapperProvider {

    INSTANCE;

    private static final Logger logger = LoggerFactory.getLogger(DefaultJacksonObjectMapperProvider.class);

    @SuppressWarnings("NonFinalFieldInEnum")
    private static boolean noticed;

    @Override
    public ObjectMapper newObjectMapper() {
        final JsonMapper.Builder jsonMapperBuilder = JsonMapper.builder();
        // Create the default ObjectMapper with the modules provided by SPI.
        jsonMapperBuilder.findAndAddModules();
        final ObjectMapper mapper = jsonMapperBuilder.build();
        final Set<Object> registeredModuleIds = mapper.getRegisteredModuleIds();
        if (registeredModuleIds.contains("com.fasterxml.jackson.module.scala.DefaultScalaModule")) {
            // Disallow null values for non-Option fields. Option[A] is commonly preferred.
            mapper.enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES);
        }

        if (!noticed) {
            if (!registeredModuleIds.isEmpty()) {
                logger.debug("Available Jackson Modules: {}", registeredModuleIds);
            }
            noticed = true;
        }
        return mapper;
    }
}
