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

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.util.Exceptions;

/**
 * A {@link RuntimeException} raised when a {@link Service} failed to find a resource.
 */
public final class ResourceNotFoundException extends HttpResponseException {

    private static final long serialVersionUID = 1268757990666737813L;

    private static final ResourceNotFoundException INSTANCE =
            Exceptions.clearTrace(new ResourceNotFoundException());

    /**
     * Returns a {@link ResourceNotFoundException} which may be a singleton or a new instance, depending on
     * whether {@link Flags#verboseExceptions() the verbose exception mode} is enabled.
     */
    public static ResourceNotFoundException get() {
        return Flags.verboseExceptions() ? new ResourceNotFoundException() : INSTANCE;
    }

    /**
     * Creates a new instance.
     */
    private ResourceNotFoundException() {
        super(HttpStatus.NOT_FOUND);
    }
}
