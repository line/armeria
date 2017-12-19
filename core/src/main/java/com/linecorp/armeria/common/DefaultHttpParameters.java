/*
 * Copyright 2017 LINE Corporation
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

import static io.netty.util.HashingStrategy.JAVA_HASHER;

import io.netty.handler.codec.DefaultHeaders;
import io.netty.util.HashingStrategy;

/**
 * Default implementation of {@link HttpParameters} which uses the {@link HashingStrategy#JAVA_HASHER}
 * to support case-sensitive parameter names.
 */
public class DefaultHttpParameters
        extends DefaultHeaders<String, String, HttpParameters> implements HttpParameters {

    /**
     * Creates a new instance with a default value converter.
     */
    @SuppressWarnings("unchecked")
    public DefaultHttpParameters() {
        super(JAVA_HASHER, StringValueConverter.INSTANCE);
    }

    /**
     * Create a new instance with a default value converter and the specified hint of array size.
     *
     * @param arraySizeHint A hint as to how large the hash data structure should be.
     *        The next positive power of two will be used. An upper bound may be enforced.
     */
    @SuppressWarnings("unchecked")
    public DefaultHttpParameters(int arraySizeHint) {
        super(JAVA_HASHER, StringValueConverter.INSTANCE, NameValidator.NOT_NULL, arraySizeHint);
    }
}
