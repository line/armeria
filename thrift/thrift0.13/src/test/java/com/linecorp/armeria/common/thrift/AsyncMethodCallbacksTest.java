/*
 * Copyright 2018 LINE Corporation
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

import static com.linecorp.armeria.common.util.UnmodifiableFuture.completedFuture;
import static com.linecorp.armeria.common.util.UnmodifiableFuture.exceptionallyCompletedFuture;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CompletionException;

import org.apache.thrift.async.AsyncMethodCallback;
import org.junit.Test;

import com.linecorp.armeria.internal.testing.AnticipatedException;

public class AsyncMethodCallbacksTest {

    @Test
    public void transferSuccess() {
        @SuppressWarnings("unchecked")
        final AsyncMethodCallback<String> callback = mock(AsyncMethodCallback.class);
        AsyncMethodCallbacks.transfer(completedFuture("foo"), callback);

        verify(callback, only()).onComplete("foo");
    }

    @Test
    public void transferFailure_Exception() {
        @SuppressWarnings("unchecked")
        final AsyncMethodCallback<String> callback = mock(AsyncMethodCallback.class);
        AsyncMethodCallbacks.transfer(exceptionallyCompletedFuture(new AnticipatedException()), callback);

        verify(callback, only()).onError(isA(AnticipatedException.class));
    }

    @Test
    public void transferFailure_Throwable() {
        @SuppressWarnings("unchecked")
        final AsyncMethodCallback<String> callback = mock(AsyncMethodCallback.class);
        AsyncMethodCallbacks.transfer(exceptionallyCompletedFuture(new Throwable("foo")), callback);

        verify(callback, only()).onError(argThat(argument -> {
            return argument instanceof CompletionException &&
                   "foo".equals(argument.getCause().getMessage());
        }));
    }

    @Test
    public void transferCallbackError() {
        @SuppressWarnings("unchecked")
        final AsyncMethodCallback<String> callback = mock(AsyncMethodCallback.class);
        doThrow(new AnticipatedException()).when(callback).onComplete(any());
        AsyncMethodCallbacks.transfer(completedFuture("foo"), callback);
        verify(callback, only()).onComplete("foo");
    }
}
