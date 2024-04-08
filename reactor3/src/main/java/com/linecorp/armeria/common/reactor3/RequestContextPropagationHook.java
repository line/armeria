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

import io.micrometer.context.ContextRegistry;
import reactor.core.publisher.Hooks;

/**
 * TBD.
 */
public final class RequestContextPropagationHook {

    private static boolean enabled;

    private RequestContextPropagationHook() {}

    /**
     * TBD.
     */
    public static synchronized void enable() {
        if (enabled) {
            return;
        }
        ContextRegistry
                .getInstance()
                .registerThreadLocalAccessor(RequestContextAccessor.getInstance());
        Hooks.enableAutomaticContextPropagation();

        enabled = true;
    }

    /**
     * TBD.
     */
    public static boolean isEnable() {
        return enabled;
    }

    /**
     * TBD.
     */
    public static synchronized void disable() {
        if (!enabled) {
            return;
        }

        Hooks.disableAutomaticContextPropagation();
        enabled = false;
    }
}
