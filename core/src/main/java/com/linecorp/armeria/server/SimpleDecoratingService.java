/*
 *  Copyright 2017 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * A {@link Service} that decorates another {@link Service}. Use {@link DecoratingService} if your
 * {@link Service} has different {@link Request} or {@link Response} type from the {@link Service} being
 * decorated.
 *
 * @param <I> the {@link Request} type of the {@link Service} being decorated
 * @param <O> the {@link Response} type of the {@link Service} being decorated
 *
 * @see Service#decorate(DecoratingServiceFunction)
 */
public abstract class SimpleDecoratingService<I extends Request, O extends Response>
        extends DecoratingService<I, O, I, O> {

    /**
     * Creates a new instance that decorates the specified {@link Service}.
     */
    protected SimpleDecoratingService(Service<I, O> delegate) {
        super(delegate);
    }
}
