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
package com.linecorp.armeria.server.annotation;

import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;

/**
 * The verbosity of exceptions logged by annotated HTTP services.
 *
 * @deprecated Use {@link LoggingService} or log exceptions using
 *             {@link ServerBuilder#exceptionHandler(com.linecorp.armeria.server.ExceptionHandler)} instead.
 */
@Deprecated
public enum ExceptionVerbosity {
    /**
     * Log all exceptions.
     */
    ALL,
    /**
     * Log exceptions which are not handled by any {@link ExceptionHandler} specified by a user.
     * However, the following exceptions would not be logged because they are from the well-known cause.
     * <ul>
     *     <li>{@link IllegalArgumentException}</li>
     *     <li>{@link HttpStatusException}</li>
     *     <li>{@link HttpResponseException}</li>
     *     <li>Other expected exceptions as defined in {@link Exceptions#isExpected(Throwable)}</li>
     * </ul>
     */
    UNHANDLED,
    /**
     * Do not log any exceptions.
     */
    NONE
}
