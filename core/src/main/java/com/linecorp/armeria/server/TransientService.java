/*
 * Copyright 2018 LINE Corporation
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
 * A {@link Service} that handles transient requests, for example, health check requests.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
@FunctionalInterface
public interface TransientService<I extends Request, O extends Response> extends Service<I, O> {

    /**
     * Returns the {@link Set} of {@link OptOutFeature}s that are disabled for this
     * {@link TransientService}. This returns {@link OptOutFeature#allOf()} if you didn't specify any
     * {@link OptOutFeature}s using {@link TransientServiceBuilder#optOutFeatures(OptOutFeature...)} when
     * you create this {@link TransientService}.
     */
    default Set<OptOutFeature> optOutFeatures() {
        return OptOutFeature.allOf();
    }
}
