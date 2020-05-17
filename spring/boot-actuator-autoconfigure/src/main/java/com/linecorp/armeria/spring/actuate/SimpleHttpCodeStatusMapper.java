/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.spring.actuate;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.health.Status;

/**
 * Copied from spring-boot-actuator 2.3.0 to avoid breaking compatibility.
 * We used spring-boot-actuator's HealthStatusHttpMapper previously
 * but it has been deprecated since 2.2.0 and deleted since 2.3.0.
 */
class SimpleHttpCodeStatusMapper {

    private final Map<String, Integer> mappings;

    SimpleHttpCodeStatusMapper() {
        final Map<String, Integer> defaultMappings = new HashMap<>();
        defaultMappings.put(Status.DOWN.getCode(), WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
        defaultMappings.put(Status.OUT_OF_SERVICE.getCode(), WebEndpointResponse.STATUS_SERVICE_UNAVAILABLE);
        mappings = getUniformMappings(defaultMappings);
    }

    int getStatusCode(Status status) {
        final String code = getUniformCode(status.getCode());
        return mappings.getOrDefault(code, WebEndpointResponse.STATUS_OK);
    }

    private static Map<String, Integer> getUniformMappings(Map<String, Integer> mappings) {
        final Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : mappings.entrySet()) {
            final String code = getUniformCode(entry.getKey());
            if (code != null) {
                result.putIfAbsent(code, entry.getValue());
            }
        }
        return Collections.unmodifiableMap(result);
    }

    @Nullable
    private static String getUniformCode(@Nullable String code) {
        if (code == null) {
            return null;
        }
        final StringBuilder builder = new StringBuilder();
        for (char ch : code.toCharArray()) {
            if (Character.isAlphabetic(ch) || Character.isDigit(ch)) {
                builder.append(Character.toLowerCase(ch));
            }
        }
        return builder.toString();
    }
}
