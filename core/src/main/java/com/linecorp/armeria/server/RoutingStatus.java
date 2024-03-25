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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A routing status of an incoming HTTP request.
 */
@UnstableApi
public enum RoutingStatus {
    /**
     * The routing completed successfully.
     */
    OK(true),

    /**
     * A <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/CORS">CORS</a> preflight request.
     */
    CORS_PREFLIGHT(true),

    /**
     * An {@code "OPTIONS * HTTP/1.1"} request.
     */
    OPTIONS(false);

    private final boolean routeMustExist;

    RoutingStatus(boolean routeMustExist) {
        this.routeMustExist = routeMustExist;
    }

    /**
     * Returns {@code true} if a {@link Route} must exist for the incoming HTTP request.
     */
    public boolean routeMustExist() {
        return routeMustExist;
    }
}
