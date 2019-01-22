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

/**
 * A {@link RuntimeException} raised when a {@link Client} is executing and the {@link ClientFactory} which the
 * {@link Client} is using is closed.
 *
 * @deprecated {@link IllegalStateException} with a message will be raised.
 */
@Deprecated
public final class ClosedClientFactoryException extends IllegalStateException {

    private static final long serialVersionUID = 6865054624299408503L;

    /**
     * Returns a new {@link ClosedClientFactoryException}.
     */
    public static ClosedClientFactoryException get() {
        return new ClosedClientFactoryException();
    }

    private ClosedClientFactoryException() {}
}
