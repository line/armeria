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

final class ParsedResourcesHolder<T extends XdsResource> {
    private final Map<String, T> parsedResources;
    private final Set<String> invalidResources;
    private final List<String> errors;

    ParsedResourcesHolder(Map<String, T> parsedResources,
                          Set<String> invalidResources,
                          List<String> errors) {
        this.parsedResources = parsedResources;
        this.invalidResources = invalidResources;
        this.errors = errors;
    }

    Map<String, T> parsedResources() {
        return parsedResources;
    }

    Set<String> invalidResources() {
        return invalidResources;
    }

    List<String> errors() {
        return errors;
    }
}
