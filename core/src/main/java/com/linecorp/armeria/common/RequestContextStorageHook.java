/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.common;

import java.util.function.Function;

/**
 * Customizes the current {@link RequestContextStorage} by applying this {@link RequestContextStorageHook} to
 * it. This hook is useful when you need to perform an additional operation when a {@link RequestContext}
 * is pushed or popped.
 */
@FunctionalInterface
public interface RequestContextStorageHook extends Function<RequestContextStorage, RequestContextStorage> {

    /**
     * Customizes the specified {@link RequestContextStorage} by applying the method to it.
     */
    @Override
    RequestContextStorage apply(RequestContextStorage contextStorage);
}
