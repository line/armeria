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

package com.linecorp.armeria.common.kotlin;

import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.kotlin.CoroutineContextService;

import kotlin.coroutines.CoroutineContext;

/**
 * Returns a {@link CoroutineContext} from a given {@link ServiceRequestContext}.
 *
 * @see CoroutineContextService
 */
@FunctionalInterface
public interface CoroutineContextProvider {

    /**
     * Returns a {@link CoroutineContext}.
     */
    CoroutineContext provide(ServiceRequestContext ctx);
}
