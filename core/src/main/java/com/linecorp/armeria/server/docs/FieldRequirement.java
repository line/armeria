/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.docs;

import com.linecorp.armeria.common.util.UnstableApi;

/**
 * The requirement level of a field.
 */
@UnstableApi
public enum FieldRequirement {
    /**
     * The field is required. The invocation will fail if the field is not specified.
     */
    REQUIRED,

    /**
     * The field is optional. The invocation will work even if the field is not specified.
     */
    OPTIONAL,

    /**
     * The requirement level is unspecified and will be handled implicitly by the serialization layer.
     */
    UNSPECIFIED
}
