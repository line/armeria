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
package com.linecorp.armeria.internal;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider.GeneralExceptionConverter;
import com.linecorp.armeria.server.annotation.ResponseConverterFunctionProvider.GeneralResponseConverter;

/**
 * A {@link Subscriber} which collects all objects produced by a {@link Publisher} as a list.
 */
public class CollectingSubscriber implements Subscriber<Object> {

    private final ServiceRequestContext ctx;
    private final HttpRequest req;
    private final CompletableFuture<HttpResponse> future;
    private final GeneralResponseConverter generalResponseConverter;
    private final GeneralExceptionConverter generalExceptionConverter;

    @Nullable
    private List<Object> objects;

    public CollectingSubscriber(ServiceRequestContext ctx, HttpRequest req,
                                CompletableFuture<HttpResponse> future,
                                GeneralResponseConverter generalResponseConverter,
                                GeneralExceptionConverter generalExceptionConverter) {
        this.ctx = requireNonNull(ctx, "ctx");
        this.req = requireNonNull(req, "req");
        this.future = requireNonNull(future, "future");
        this.generalResponseConverter = requireNonNull(generalResponseConverter, "generalResponseConverter");
        this.generalExceptionConverter = requireNonNull(generalExceptionConverter, "generalExceptionConverter");
    }

    @Override
    public void onSubscribe(Subscription s) {
        s.request(Integer.MAX_VALUE);
    }

    @Override
    public void onNext(Object o) {
        if (objects == null) {
            objects = new ArrayList<>();
        }
        objects.add(o);
    }

    @Override
    public void onError(Throwable t) {
        future.complete(generalExceptionConverter.convertException(ctx, req, t));
    }

    @Override
    public void onComplete() {
        final Object obj;
        if (objects == null) {
            obj = null;
        } else {
            switch (objects.size()) {
                case 0:
                    obj = null;
                    break;
                case 1:
                    obj = objects.get(0);
                    break;
                default:
                    obj = objects;
                    break;
            }
        }
        future.complete(generalResponseConverter.convertResponse(ctx, obj));
    }
}
