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
package com.linecorp.armeria.client.retrofit2;

import java.util.concurrent.Executor;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.client.retrofit2.ArmeriaCallFactory.ArmeriaCall;
import com.linecorp.armeria.common.HttpObject;

import okhttp3.Callback;
import okhttp3.Request;

@FunctionalInterface
interface SubscriberFactory {
    Subscriber<HttpObject> create(ArmeriaCall armeriaCall, Callback callback, Request request);

    static SubscriberFactory blocking() {
        return BlockingCallSubscriber::new;
    }

    static SubscriberFactory streaming(Executor callbackExecutor) {
        return (ArmeriaCall armeriaCall, Callback callback, Request request) -> new StreamingCallSubscriber(
                armeriaCall, callback, request, callbackExecutor);
    }
}
