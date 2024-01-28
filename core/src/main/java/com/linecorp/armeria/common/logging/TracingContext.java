/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A holder class which has the tracing(e.g. zipkin) information.
 */
public final class TracingContext {
    final long traceIdHigh;

    final long traceId;
    final String traceIdString;

    @Nullable
    final Long parentId;
    @Nullable
    final String parentIdString;

    final long spanId;
    final String spanIdString;

    final long localRootId;
    final String localRootIdString;

    /**
     * Create a newly created {@link TracingContext}.
     */
    public static TracingContext of(long traceIdHigh, long traceId, String traceIdString,
                                    @Nullable Long parentId, @Nullable String parentIdString,
                                    Long spanId, String spanIdString,
                                    long localRootId, String localRootIdString) {
        return new TracingContext(traceIdHigh, traceId, traceIdString, parentId, parentIdString, spanId,
                              spanIdString, localRootId, localRootIdString);
    }

    TracingContext(long traceIdHigh, long traceId, String traceIdString,
                   @Nullable Long parentId, @Nullable String parentIdString,
                   Long spanId, String spanIdString,
                   long localRootId, String localRootIdString) {
        this.traceIdHigh = traceIdHigh;
        this.traceId = traceId;
        this.traceIdString = requireNonNull(traceIdString, "traceIdString");
        this.parentId = parentId;
        this.parentIdString = parentIdString;
        this.spanId = spanId;
        this.spanIdString = requireNonNull(spanIdString, "spanIdString");
        this.localRootId = localRootId;
        this.localRootIdString = requireNonNull(localRootIdString, "localRootIdString");
    }

    /**
     * Returns the trace id high.
     */
    public long traceIdHigh() {
        return traceIdHigh;
    }

    /**
     * Returns the trace id of the {@link Request}.
     */
    public long traceId() {
        return traceId;
    }

    /**
     * Returns the trace id of the {@link Request}.
     */
    public String traceIdString() {
        return traceIdString;
    }

    /**
     * Returns the parent span id of the {@link Request}.
     */
    @Nullable
    public Long parentId() {
        return parentId;
    }

    /**
     * Returns the parent span id of the {@link Request}.
     */
    @Nullable
    public String parentIdString() {
        return parentIdString;
    }

    /**
     * Returns the span id of the {@link Request}.
     */
    public long spanId() {
        return spanId;
    }

    /**
     * Returns the span id of the {@link Request}.
     */
    public String spanIdString() {
        return spanIdString;
    }

    /**
     * Returns the local root id of the {@link Request}.
     */
    public long localRootId() {
        return localRootId;
    }

    /**
     * Returns the local root id of the {@link Request}.
     */
    public String localRootIdString() {
        return localRootIdString;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("traceIdHigh", traceIdHigh)
                          .add("traceId", traceId)
                          .add("traceIdString", traceIdString)
                          .add("parentId", parentId)
                          .add("parentIdString", parentIdString)
                          .add("spanId", spanId)
                          .add("spanIdString", spanIdString)
                          .add("localRootId", localRootId)
                          .add("localRootIdString", localRootIdString).toString();
    }
}
