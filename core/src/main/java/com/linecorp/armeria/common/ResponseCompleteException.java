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
package com.linecorp.armeria.common;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A special {@link CancellationException} that aborts an {@link HttpRequest} after the corresponding
 * {@link HttpResponse} is completed.
 */
@UnstableApi
public final class ResponseCompleteException extends CancellationException {

    private static final long serialVersionUID = 6090278381004263949L;

    private static final ResponseCompleteException INSTANCE = new ResponseCompleteException();

    /**
     * Returns the singleton {@link ResponseCompleteException}.
     */
    public static ResponseCompleteException get() {
        return INSTANCE;
    }

    private ResponseCompleteException() {
        super(null, null, false, false);
    }
}
