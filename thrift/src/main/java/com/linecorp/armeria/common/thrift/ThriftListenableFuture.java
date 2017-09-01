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

package com.linecorp.armeria.common.thrift;

import org.apache.thrift.async.AsyncMethodCallback;

import com.google.common.util.concurrent.AbstractFuture;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * A {@link ListenableFuture} that can be passed in as an {@link AsyncMethodCallback}
 * when making an asynchronous client-side Thrift RPC.
 */
public class ThriftListenableFuture<T> extends AbstractFuture<T> implements AsyncMethodCallback<T> {
    @Override
    public void onComplete(T t) {
        set(t);
    }

    @Override
    public void onError(Exception e) {
        setException(e);
    }
}
