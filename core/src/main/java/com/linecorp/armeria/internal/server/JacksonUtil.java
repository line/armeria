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

import java.lang.reflect.InvocationTargetException;
import java.util.List;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.ImmutableList;

public final class JacksonUtil {

    private static final ObjectMapper defaultMapper;

    static {
        final JsonMapper.Builder jsonMapperBuilder = JsonMapper.builder();

        final List<String> additionalModules = ImmutableList.of(
                "com.fasterxml.jackson.module.scala.DefaultScalaModule",
                "com.fasterxml.jackson.module.kotlin.KotlinModule");

        // Add the additional modules if they are in the classpath
        for (String moduleClassName : additionalModules) {
            try {
                final Class<?> moduleClass = Class.forName(moduleClassName);
                final Module module = (Module) moduleClass.getDeclaredConstructor().newInstance();
                jsonMapperBuilder.addModule(module);
            } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException |
                    IllegalAccessException | InvocationTargetException ignored) {
            }
        }

        defaultMapper = jsonMapperBuilder.build();
    }

    public static ObjectMapper defaultObjectMapper() {
        return defaultMapper;
    }

    private JacksonUtil() {}
}
