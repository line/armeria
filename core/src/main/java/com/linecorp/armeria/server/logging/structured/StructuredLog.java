/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.logging.structured;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.TextFormatter;

/**
 * A representation and constructor of a service log which holds only very common fields that are protocol
 * agnostic.
 */
public abstract class StructuredLog {
    private final long timestampMillis;
    private final long responseTimeNanos;
    private final long requestSize;
    private final long responseSize;

    /**
     * Creates a new instance.
     */
    protected StructuredLog(long timestampMillis, long responseTimeNanos, long requestSize, long responseSize) {
        this.timestampMillis = timestampMillis;
        this.responseTimeNanos = responseTimeNanos;
        this.requestSize = requestSize;
        this.responseSize = responseSize;
    }

    /**
     * Constructs {@link StructuredLog} from {@link RequestContext} and {@link RequestLog}.
     * Can be used as {@link StructuredLogBuilder}.
     */
    protected StructuredLog(RequestLog reqLog) {
        timestampMillis = reqLog.requestStartTimeMillis();
        responseTimeNanos = reqLog.totalDurationNanos();

        requestSize = reqLog.requestLength();
        responseSize = reqLog.responseLength();
    }

    /**
     * Returns the timestamp in ms of the time that the request has been received.
     *
     * @return timestamp in ms of the time the request received
     */
    @JsonProperty
    public long timestampMillis() {
        return timestampMillis;
    }

    /**
     * Returns the duration in ms of the time that is taken to process the request.
     *
     * @return duration in ns that was taken to process the request
     */
    @JsonProperty
    public long responseTimeNanos() {
        return responseTimeNanos;
    }

    /**
     * Returns the size of request payload.
     *
     * @return size of the request payload in bytes
     */
    @JsonProperty
    public long requestSize() {
        return requestSize;
    }

    /**
     * Returns the size of response payload.
     *
     * @return size of the response payload in bytes
     */
    @JsonProperty
    public long responseSize() {
        return responseSize;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("timestamp", TextFormatter.epoch(timestampMillis))
                          .add("responseTime", TextFormatter.elapsed(responseTimeNanos))
                          .add("requestSize", TextFormatter.size(requestSize))
                          .add("responseSize", TextFormatter.size(responseSize))
                          .toString();
    }
}
