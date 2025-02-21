/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds;

import java.util.Map;
import java.util.Map.Entry;

import com.google.common.collect.ImmutableMap;
import com.google.protobuf.Any;

final class FilterUtil {

    static Map<String, ParsedFilterConfig> toParsedFilterConfigs(Map<String, Any> filterConfigMap) {
        final ImmutableMap.Builder<String, ParsedFilterConfig> filterConfigsBuilder = ImmutableMap.builder();
        for (Entry<String, Any> e: filterConfigMap.entrySet()) {
            filterConfigsBuilder.put(e.getKey(), ParsedFilterConfig.of(e.getKey(), e.getValue()));
        }
        return filterConfigsBuilder.buildKeepingLast();
    }

    private FilterUtil() {}
}
