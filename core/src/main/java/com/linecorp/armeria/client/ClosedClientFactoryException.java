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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} raised when a {@link Client} is executing and the {@link ClientFactory} which the
 * {@link Client} is using is closed.
 */
public final class ClosedClientFactoryException extends RuntimeException {

    private static final long serialVersionUID = 6865054624299408503L;

    private static final ClosedClientFactoryException INSTANCE =
            Exceptions.clearTrace(new ClosedClientFactoryException());

    /**
     * Returns a {@link ClosedClientFactoryException} which may be a singleton or a new instance, depending on
     * whether {@link Flags#verboseExceptions() the verbose exception mode} is enabled.
     */
    public static ClosedClientFactoryException get() {
        return Flags.verboseExceptions() ? new ClosedClientFactoryException() : INSTANCE;
    }

    /**
     * Creates a new instance.
     */
    private ClosedClientFactoryException() {}
}
