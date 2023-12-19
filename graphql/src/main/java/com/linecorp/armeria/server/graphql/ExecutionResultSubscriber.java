/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.graphql;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.stream.NoopSubscription;

import graphql.ExecutionResult;

@SuppressWarnings("ReactiveStreamsSubscriberImplementation")
final class ExecutionResultSubscriber implements Subscriber<ExecutionResult> {
    private static final Logger logger = LoggerFactory.getLogger(ExecutionResultSubscriber.class);
    private final GraphqlSubProtocol protocol;
    private final String operationId;

    @Nullable
    private Subscription subscription;

    ExecutionResultSubscriber(String operationId, GraphqlSubProtocol protocol) {
        this.operationId = operationId;
        this.protocol = protocol;
    }

    @Override
    public void onSubscribe(Subscription s) {
        if (subscription != null) {
            /*
            A Subscriber MUST call Subscription.cancel() on the given Subscription after an onSubscribe signal
            if it already has an active Subscription.
             */
            s.cancel();
            return;
        }
        subscription = s;
        requestMore();
    }

    @Override
    public void onNext(ExecutionResult executionResult) {
        assert subscription != null;
        try {
            if (executionResult.getErrors().isEmpty()) {
                protocol.sendResult(operationId, executionResult);
                requestMore();
            } else {
                protocol.sendGraphqlErrors(executionResult.getErrors());
                subscription.cancel();
            }
        } catch (JsonProcessingException e) {
            protocol.completeWithError(e);
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }

    @Override
    public void onError(Throwable t) {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onError", t);
        subscription = null;
        protocol.completeWithError(t);
    }

    @Override
    public void onComplete() {
        /*
        Subscriber.onComplete() and Subscriber.onError(Throwable t) MUST consider
        the Subscription cancelled after having received the signal.
         */
        logger.trace("onComplete");
        subscription = null;
        protocol.complete();
    }

    public void setCompleted() {
        if (subscription == null) {
            subscription = NoopSubscription.get();
        }
        subscription.cancel();
    }

    private void requestMore() {
        if (subscription != null) {
            subscription.request(1);
        }
    }
}
