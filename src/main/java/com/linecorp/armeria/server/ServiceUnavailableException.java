/*
 * Copyright 2015 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} that is raised when a requested invocation cannot be served.
 */
public final class ServiceUnavailableException extends RuntimeException {

    private static final long serialVersionUID = -9092895165959388396L;


    private static final ServiceUnavailableException INSTANCE =
            Exceptions.clearTrace(new ServiceUnavailableException());

    /**
     * Returns a {@link ServiceUnavailableException} which may be a singleton or a new instance, depending on
     * whether {@link Exceptions#isVerbose() the verbose mode} is enabled.
     */
    public static ServiceUnavailableException get() {
        return Exceptions.isVerbose() ? new ServiceUnavailableException() : INSTANCE;
    }

    /**
     * Creates a new instance.
     */
    private ServiceUnavailableException() {}
}
