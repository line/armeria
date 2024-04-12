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

package com.linecorp.armeria.common.reactor3;

import com.linecorp.armeria.common.RequestContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/**
 * Utility class to keep {@link RequestContext} during
 * <a href="https://github.com/reactor/reactor-core">Reactor</a> operations
 * with <a href="https://docs.micrometer.io/context-propagation/reference/index.html">
 * Context-propagation</a>.
 */
public final class RequestContextPropagationHook {

    private static volatile boolean enabled;

    /**
     * Enable <a href="https://docs.micrometer.io/context-propagation/reference/index.html">
     * Context-propagation</a> to keep {@link RequestContext} during
     * Reactor operations.
     * </p>
     * Please note that enable {@link RequestContextPropagationHook} at the
     * start of the application. otherwise, {@link RequestContext} may not be keep.
     */
    public static synchronized void enable() {
        if (enabled) {
            return;
        }
        Hooks.enableAutomaticContextPropagation();
        enabled = true;
    }

    /**
     * It returns whether the {@link RequestContextPropagationHook} is enabled.
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * It disable {@link RequestContextPropagationHook}. {@link RequestContext}
     * will not be keep during both {@link Mono} and {@link Flux} Operations.
     */
    public static synchronized void disable() {
        if (!enabled) {
            return;
        }

        Hooks.disableAutomaticContextPropagation();
        enabled = false;
    }

    private RequestContextPropagationHook() {}
}
