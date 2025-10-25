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

import static com.linecorp.armeria.internal.server.annotation.AnnotatedServiceLogUtil.maybeUnwrapFuture;

import org.jspecify.annotations.Nullable;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.logging.BeanFieldInfo;

final class AnnotatedResponse {

    @Nullable
    private final Object rawValue;
    private final BeanFieldInfo beanFieldInfo;

    AnnotatedResponse(@Nullable Object rawValue, BeanFieldInfo beanFieldInfo) {
        this.rawValue = rawValue;
        this.beanFieldInfo = beanFieldInfo;
    }

    @Nullable
    Object rawValue() {
        return rawValue;
    }

    @Nullable
    Object value() {
        return maybeUnwrapFuture(rawValue);
    }

    BeanFieldInfo beanFieldInfo() {
        return beanFieldInfo;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("value", value())
                          .toString();
    }
}
