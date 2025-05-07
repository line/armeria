/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.internal.server.annotation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AnnotatedRequest;
import com.linecorp.armeria.common.annotation.Nullable;

public final class DefaultAnnotatedRequest implements AnnotatedRequest {

    private final List<@Nullable Object> parameters;

    DefaultAnnotatedRequest(Object[] parameters) {
        this.parameters = Collections.unmodifiableList(Arrays.asList(parameters));
    }

    @Override
    public List<@Nullable Object> parameters() {
        return parameters;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("parameters", parameters)
                          .toString();
    }
}
