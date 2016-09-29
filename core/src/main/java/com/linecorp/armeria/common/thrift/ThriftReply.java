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

package com.linecorp.armeria.common.thrift;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AbstractRpcResponse;
import com.linecorp.armeria.common.RpcResponse;

/**
 * A Thrift {@link RpcResponse}.
 */
public final class ThriftReply extends AbstractRpcResponse {

    private final int seqId;

    /**
     * Creates a new incomplete instance.
     */
    public ThriftReply(int seqId) {
        this.seqId = seqId;
    }

    /**
     * Creates a new successfully complete instance.
     */
    public ThriftReply(int seqId, Object result) {
        super(result);
        this.seqId = seqId;
    }

    /**
     * Creates a new exceptionally complete instance.
     */
    public ThriftReply(int seqId, Throwable cause) {
        super(cause);
        this.seqId = seqId;
    }

    /**
     * Returns the {@code seqId} of the reply.
     */
    public int seqId() {
        return seqId;
    }

    @Override
    public String toString() {
        if (!isDone()) {
            return super.toString();
        }

        if (isCompletedExceptionally()) {
            return MoreObjects.toStringHelper(this)
                              .add("seqId", seqId())
                              .add("cause", getCause()).toString();
        } else {
            return MoreObjects.toStringHelper(this)
                              .add("seqId", seqId())
                              .add("value", getNow(null)).toString();
        }
    }
}
