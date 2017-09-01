/*
 * Copyright 2016 LINE Corporation
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

/**
 * A listener that is notified when {@linkplain ServiceRequestContext#requestTimeoutMillis() request timeout}
 * setting is changed.
 *
 * <p>Note: This interface is meant for internal use by server-side protocol implementation to reschedule
 * a timeout task when a user updates the request timeout configuration.
 */
@FunctionalInterface
public interface RequestTimeoutChangeListener {
    /**
     * Invoked when the request timeout of the current request has been changed.
     *
     * @param newRequestTimeoutMillis the new timeout value in milliseconds. {@code 0} if disabled.
     */
    void onRequestTimeoutChange(long newRequestTimeoutMillis);
}
