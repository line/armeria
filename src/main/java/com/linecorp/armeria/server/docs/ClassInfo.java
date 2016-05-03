/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.docs;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

interface ClassInfo {
    @JsonProperty
    String name();

    @JsonProperty
    default String simpleName() {
        return name().substring(name().lastIndexOf('.') + 1);
    }

    @JsonProperty
    default String packageName() {
        return name().substring(0, name().lastIndexOf('.'));
    }

    @JsonProperty
    List<FieldInfo> fields();

    @JsonProperty
    List<Object> constants();

    @JsonProperty
    String docString();
}
