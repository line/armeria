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

import java.util.concurrent.ExecutorService;

import javax.annotation.Nonnull;

import com.google.common.base.MoreObjects;

class DefaultContextAwareExecutorService extends AbstractContextAwareExecutorService<ExecutorService>
        implements ContextAwareExecutorService {
    private final RequestContext context;

    DefaultContextAwareExecutorService(RequestContext context, ExecutorService executor) {
        super(executor);
        this.context = context;
    }

    @Override
    public final RequestContext context() {
        return context;
    }

    @Override
    @Nonnull
    RequestContext contextOrNull() {
        return context;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("context", contextOrNull())
                          .add("executor", executor)
                          .toString();
    }
}
