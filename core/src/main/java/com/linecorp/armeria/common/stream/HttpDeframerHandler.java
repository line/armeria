/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.stream;

import org.reactivestreams.Publisher;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * An {@link HttpDeframerHandler} that decodes a stream of {@link HttpObject}s to N objects.
 *
 * @param <T> the result type of being deframed
 */
@UnstableApi
public interface HttpDeframerHandler<T> {

    /**
     * Decodes a stream of {@link HttpData}s to N objects.
     * This method will be called whenever an {@link HttpData} is signaled from {@link Publisher}.
     */
    void process(HttpDeframerInput in, HttpDeframerOutput<T> out) throws Exception;

    /**
     * Decodes an informational {@link ResponseHeaders} to N objects.
     */
    default void processInformationalHeaders(ResponseHeaders in, HttpDeframerOutput<T> out) throws Exception {}

    /**
     * Decodes a non-informational {@link HttpHeaders} to N objects.
     */
    default void processHeaders(HttpHeaders in, HttpDeframerOutput<T> out) throws Exception {}

    /**
     * Decodes a {@link HttpHeaders trailers} to N objects.
     */
    default void processTrailers(HttpHeaders in, HttpDeframerOutput<T> out) throws Exception {}

    /**
     * Invoked when a {@link Throwable} is raised while deframing.
     */
    default void processOnError(Throwable cause) {}
}
