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

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;
import java.util.function.Function;

import com.google.common.base.MoreObjects;

final class PropagatingContextAwareExecutor extends AbstractContextAwareExecutor<Executor> {

    static PropagatingContextAwareExecutor of(Executor executor) {
        requireNonNull(executor, "executor");
        if (executor instanceof PropagatingContextAwareExecutor) {
            return (PropagatingContextAwareExecutor) executor;
        } else {
            return new PropagatingContextAwareExecutor(executor);
        }
    }

    private PropagatingContextAwareExecutor(Executor executor) {
        super(executor);
    }

    @Override
    RequestContext contextOrNull() {
        return RequestContext.mapCurrent(Function.identity(), LogRequestContextWarningOnce.INSTANCE);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("executor", withoutContext())
                          .toString();
    }
}
