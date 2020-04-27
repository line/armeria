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

package com.linecorp.armeria.unsafe;

import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * An {@link AggregatedHttpRequest} using pooled buffers for the content. Make sure to call
 * {@link AutoCloseable#close()} on this response or the {@code content} to release pooled resources.
 */
public interface PooledAggregatedHttpRequest extends AggregatedHttpRequest, SafeCloseable {

    /**
     * {@inheritDoc}
     */
    @Override
    PooledHttpData content();

    @Override
    PooledHttpRequest toHttpRequest();
}
