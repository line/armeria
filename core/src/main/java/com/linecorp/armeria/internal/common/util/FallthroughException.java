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
package com.linecorp.armeria.internal.common.util;

import com.linecorp.armeria.server.annotation.ExceptionHandlerFunction;
import com.linecorp.armeria.server.annotation.RequestConverterFunction;
import com.linecorp.armeria.server.annotation.ResponseConverterFunction;

/**
 * A {@link RuntimeException} raised for falling through to the next something. It would be raised from
 * {@link ExceptionHandlerFunction}, {@link RequestConverterFunction} and/or {@link ResponseConverterFunction}.
 */
public final class FallthroughException extends RuntimeException {

    private static final long serialVersionUID = 3856883467407862925L;

    private static final FallthroughException INSTANCE = new FallthroughException();

    /**
     * Returns a singleton {@link FallthroughException}.
     */
    public static FallthroughException get() {
        return INSTANCE;
    }

    private FallthroughException() {
        super((Throwable) null);
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }
}
