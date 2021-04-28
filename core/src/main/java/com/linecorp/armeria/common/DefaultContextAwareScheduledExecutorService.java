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

import java.util.concurrent.ScheduledExecutorService;

import com.google.common.base.MoreObjects;

final class DefaultContextAwareScheduledExecutorService
        extends AbstractContextAwareScheduledExecutorService implements ContextAwareScheduledExecutorService {

    private final RequestContext context;

    DefaultContextAwareScheduledExecutorService(RequestContext context, ScheduledExecutorService executor) {
        super(executor);
        this.context = context;
    }

    @Override
    public RequestContext context() {
        return context;
    }

    @Override
    public RequestContext contextOrNull() {
        return context;
    }

    @Override
    public ScheduledExecutorService withoutContext() {
        return executor;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("executor", executor)
                          .add("context", contextOrNull())
                          .toString();
    }
}
