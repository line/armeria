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

import org.apache.thrift.TFieldRequirementType;

enum RequirementType {
    REQUIRED,
    OPTIONAL,
    DEFAULT;

    static RequirementType of(byte requirementType) {
        switch (requirementType) {
        case TFieldRequirementType.REQUIRED:
            return RequirementType.REQUIRED;
        case TFieldRequirementType.OPTIONAL:
            return RequirementType.OPTIONAL;
        case TFieldRequirementType.DEFAULT:
            return RequirementType.DEFAULT;
        default:
            throw new IllegalArgumentException("unknown requirement type: " + requirementType);
        }
    }
}
