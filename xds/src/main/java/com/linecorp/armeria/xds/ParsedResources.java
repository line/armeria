/*
 * Copyright 2023 LINE Corporation
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

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;

abstract class ParsedResources {
    private final XdsType type;
    private final Map<String, Object> parsedResources;
    private final Map<String, Throwable> invalidResources;
    private final List<String> errors;

    ParsedResources(XdsType type, Map<String, Object> parsedResources,
                    Map<String, Throwable> invalidResources) {
        this.type = type;
        this.parsedResources = parsedResources;
        this.invalidResources = invalidResources;
        errors = invalidResources.values().stream()
                                 .map(Throwable::getMessage)
                                 .collect(ImmutableList.toImmutableList());
    }

    XdsType type() {
        return type;
    }

    Map<String, Object> parsedResources() {
        return parsedResources;
    }

    Map<String, Throwable> invalidResources() {
        return invalidResources;
    }

    List<String> errors() {
        return errors;
    }

    static final class DeltaParsedResources extends ParsedResources {

        private final Set<String> removed;

        DeltaParsedResources(XdsType type, Map<String, Object> parsedResources,
                             Map<String, Throwable> invalidResources, Set<String> removed) {
            super(type, parsedResources, invalidResources);
            this.removed = removed;
        }

        Set<String> removed() {
            return removed;
        }
    }

    static final class SotwParsedResources extends ParsedResources {

        private final boolean fullStateOfTheWorld;

        SotwParsedResources(XdsType type, Map<String, Object> parsedResources,
                            Map<String, Throwable> invalidResources, boolean fullStateOfTheWorld) {
            super(type, parsedResources, invalidResources);
            this.fullStateOfTheWorld = fullStateOfTheWorld;
        }

        boolean isFullStateOfTheWorld() {
            return fullStateOfTheWorld;
        }
    }
}
