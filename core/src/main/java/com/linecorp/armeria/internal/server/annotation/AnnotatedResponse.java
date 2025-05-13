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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.BeanFieldInfo;

final class AnnotatedResponse {

    @Nullable
    private final Object value;
    private final BeanFieldInfo beanFieldInfo;

    AnnotatedResponse(@Nullable Object value, BeanFieldInfo beanFieldInfo) {
        this.value = value;
        this.beanFieldInfo = beanFieldInfo;
    }

    @Nullable
    public Object value() {
        return value;
    }

    public BeanFieldInfo beanFieldInfo() {
        return beanFieldInfo;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("value", value)
                          .toString();
    }
}
