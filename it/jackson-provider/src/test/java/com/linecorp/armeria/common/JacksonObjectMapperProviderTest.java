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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.internal.server.JacksonUtil;

class JacksonObjectMapperProviderTest {

    @Test
    void overrideDefaultJacksonModules() {
        final ObjectMapper objectMapper = JacksonUtil.newDefaultObjectMapper();
        final Set<Object> ids = objectMapper.getRegisteredModuleIds();
        assertThat(ids).hasSize(1);
        assertThat(ids).first().isEqualTo("com.fasterxml.jackson.module.kotlin.KotlinModule");
    }
}
