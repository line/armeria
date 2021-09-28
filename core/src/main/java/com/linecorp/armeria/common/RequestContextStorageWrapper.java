/*
 * Copyright 2020 LINE Corporation
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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractUnwrappable;

/**
 * Wraps an existing {@link RequestContextStorage}.
 *
 * @see RequestContextStorage#hook(Function)
 */
public class RequestContextStorageWrapper
        extends AbstractUnwrappable<RequestContextStorage> implements RequestContextStorage {

    /**
     * Creates a new instance that wraps the specified {@link RequestContextStorage}.
     */
    protected RequestContextStorageWrapper(RequestContextStorage delegate) {
        super(delegate);
    }

    @Nullable
    @Override
    public <T extends RequestContext> T push(RequestContext toPush) {
        return unwrap().push(toPush);
    }

    @Override
    public void pop(RequestContext current, @Nullable RequestContext toRestore) {
        unwrap().pop(current, toRestore);
    }

    @Nullable
    @Override
    public <T extends RequestContext> T currentOrNull() {
        return unwrap().currentOrNull();
    }
}
