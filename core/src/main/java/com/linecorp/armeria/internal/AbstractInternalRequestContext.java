/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import com.linecorp.armeria.common.AbstractRequestContext;
import com.linecorp.armeria.common.RequestContext;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

/**
 * An interface defining internal-only methods of a {@link com.linecorp.armeria.common.RequestContext}. These
 * methods should be used with care, generally only in generic services and protocol implementations, not user
 * business logic.
 */
public abstract class AbstractInternalRequestContext extends AbstractRequestContext {

    /**
     * Returns the {@link ByteBufAllocator} for this {@link AbstractInternalRequestContext}.
     */
    protected ByteBufAllocator alloc() {
        return UnpooledByteBufAllocator.DEFAULT;
    }

    /**
     * Returns the {@link ByteBufAllocator} in the provided {@link RequestContext}, or
     * {@link UnpooledByteBufAllocator#DEFAULT} if it does not contain one. Only for use in internal code.
     */
    public static ByteBufAllocator alloc(RequestContext ctx) {
        if (ctx instanceof AbstractInternalRequestContext) {
            return ((AbstractInternalRequestContext) ctx).alloc();
        }
        return UnpooledByteBufAllocator.DEFAULT;
    }
}
