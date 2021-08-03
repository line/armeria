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

import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A Java SPI (Service Provider Interface) for the default
 * <a href="https://github.com/FasterXML/jackson">Jackson</a> {@link ObjectMapper}.
 */
@UnstableApi
@FunctionalInterface
public interface JacksonObjectMapperProvider {

    /**
     * Returns the default Jackson {@link ObjectMapper}s used for serializing and deserializing an object
     * to and from JSON.
     */
    ObjectMapper newObjectMapper();
}
