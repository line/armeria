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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.common.collect.Iterables;

import com.linecorp.armeria.server.JacksonModuleProvider;

public final class JacksonUtil {

    private static final Logger logger = LoggerFactory.getLogger(JacksonUtil.class);

    private static boolean noticed;

    public static ObjectMapper newDefaultObjectMapper() {
        final JsonMapper.Builder jsonMapperBuilder = JsonMapper.builder();
        final ServiceLoader<JacksonModuleProvider> providers = ServiceLoader.load(JacksonModuleProvider.class);
        if (Iterables.isEmpty(providers)) {
            jsonMapperBuilder.findAndAddModules();
        } else {
            for (JacksonModuleProvider provider : providers) {
                final List<Module> modules = provider.modules();
                requireNonNull(modules, "provider.modules() returned null");
                jsonMapperBuilder.addModules(modules);
            }
        }
        final JsonMapper mapper = jsonMapperBuilder.build();
        if (!noticed) {
            logger.debug("Available Jackson Modules: {}", mapper.getRegisteredModuleIds());
            noticed = true;
        }
        return mapper;
    }

    private JacksonUtil() {}
}
