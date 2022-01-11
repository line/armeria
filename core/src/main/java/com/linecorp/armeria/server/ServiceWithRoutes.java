/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.server;

import java.util.Set;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * An interface that enables getting all the {@link Route}s where a {@link Service} should be bound.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public interface ServiceWithRoutes<I extends Request, O extends Response> extends Service<I, O> {
    /**
     * Returns the set of {@link Route}s to which this {@link Service} is bound.
     */
    Set<Route> routes();
}
