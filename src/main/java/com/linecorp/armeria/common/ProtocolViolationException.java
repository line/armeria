/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common;

public class ProtocolViolationException extends RuntimeException {
    private static final long serialVersionUID = 4674394621849790490L;

    public ProtocolViolationException() {}

    public ProtocolViolationException(String message) {
        super(message);
    }

    public ProtocolViolationException(String message, Throwable cause) {
        super(message, cause);
    }

    public ProtocolViolationException(Throwable cause) {
        super(cause);
    }

    protected ProtocolViolationException(String message, Throwable cause, boolean enableSuppression,
                                         boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
