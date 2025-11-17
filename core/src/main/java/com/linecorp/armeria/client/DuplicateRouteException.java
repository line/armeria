/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client;

/**
 * A {@link RuntimeException} thrown when attempting to register a route
 * that conflicts with an existing route for the same path.
 *
 * <p>This exception is raised when multiple routes share an identical path
 * and thus cannot be uniquely distinguished.</p>
 */
public final class DuplicateRouteException extends RuntimeException {

    private static final long serialVersionUID = 4679512839761213302L;

    /**
     * Creates a new instance with the error message.
     */
    public DuplicateRouteException(String errorMsg) {
        super(errorMsg);
    }
}
