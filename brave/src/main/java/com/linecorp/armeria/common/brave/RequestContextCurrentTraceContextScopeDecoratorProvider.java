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

package com.linecorp.armeria.common.brave;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.RequestContextStorageListener;
import com.linecorp.armeria.common.RequestContextStorageListenerProvider;
import com.linecorp.armeria.common.annotation.UnstableApi;

import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.TraceContext;

/**
 * Creates a new {@link RequestContextStorageListener} that decorates the current {@link TraceContext} with
 * {@link ScopeDecorator}s when a {@link RequestContext} is pushed into the {@link RequestContextStorage}.
 * The applied {@link ScopeDecorator}s will be removed when a {@link RequestContext} is popped from the
 * {@link RequestContextStorage}.
 */
@UnstableApi
public class RequestContextCurrentTraceContextScopeDecoratorProvider
        implements RequestContextStorageListenerProvider {

    @Override
    public RequestContextStorageListener newStorageListener() {
        return RequestContextCurrentTraceContextScopeDecorator.INSTANCE;
    }
}
