/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A factory for creating a {@link CircuitBreakerClientHandler}.
 */
@UnstableApi
public interface CircuitBreakerClientHandlerFactory<CB, I extends Request>  {

    /**
     * Generates a {@link CircuitBreakerClientHandler}. One may override this method
     * to use a custom {@link CircuitBreakerClientHandler} with {@link CircuitBreakerClient}.
     */
    CircuitBreakerClientHandler<I> generateHandler(ClientCircuitBreakerGenerator<CB> mapping);
}
