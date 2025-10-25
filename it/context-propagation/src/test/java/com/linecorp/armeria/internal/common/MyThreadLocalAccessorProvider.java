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

package com.linecorp.armeria.internal.common;

import org.jspecify.annotations.Nullable;

import com.linecorp.armeria.internal.common.context.ThreadLocalAccessorProvider;

import io.micrometer.context.ThreadLocalAccessor;

public final class MyThreadLocalAccessorProvider implements ThreadLocalAccessorProvider {

    static final class MyThreadLocalAccessor implements ThreadLocalAccessor<String> {

        public static final ThreadLocal<String> THREAD_LOCAL = new ThreadLocal<>();

        @Override
        public Object key() {
            return MyThreadLocalAccessor.class;
        }

        @Override
        public String getValue() {
            return THREAD_LOCAL.get();
        }

        @Override
        public void setValue(@Nullable String s) {
            THREAD_LOCAL.set(s);
        }

        @Override
        public void setValue() {
            THREAD_LOCAL.set(null);
        }
    }

    @Override
    public ThreadLocalAccessor<?> threadLocalAccessor() {
        return new MyThreadLocalAccessor();
    }
}
