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

package com.linecorp.armeria.internal.common;

import static com.linecorp.armeria.common.util.Exceptions.throwIfFatal;
import static java.util.Objects.requireNonNull;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.NoopSubscriber;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

public abstract class AbstractSplitHttpMessage implements StreamMessage<HttpData> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractSplitHttpMessage.class);

    protected final EventExecutor upstreamExecutor;

    protected AbstractSplitHttpMessage(EventExecutor executor) {
        upstreamExecutor = requireNonNull(executor, "executor");
    }

    protected abstract static class BodySubscriber implements Subscriber<HttpObject>, Subscription {

        protected boolean completing;

        protected volatile boolean notifyCancellation;
        protected boolean usePooledObject;

        @Nullable
        protected volatile Subscriber<? super HttpData> downstream;

        @Nullable
        protected volatile Subscription upstream;

        @Nullable
        protected volatile EventExecutor executor;

        @Nullable
        protected volatile Throwable cause;

        protected volatile boolean cancelCalled;

        protected void initDownstream(Subscriber<? super HttpData> downstream, EventExecutor executor,
                                    SubscriptionOption... options) {
            assert executor.inEventLoop();

            this.executor = executor;
            for (SubscriptionOption option : options) {
                if (option == SubscriptionOption.NOTIFY_CANCELLATION) {
                    notifyCancellation = true;
                } else if (option == SubscriptionOption.WITH_POOLED_OBJECTS) {
                    usePooledObject = true;
                }
            }

            try {
                downstream.onSubscribe(this);
                final Throwable cause = this.cause;
                if (cause != null) {
                    onError0(cause, downstream);
                } else if (completing) {
                    onComplete0(downstream);
                }
            } catch (Throwable t) {
                throwIfFatal(t);
                logger.warn("Subscriber should not throw an exception. subscriber: {}", downstream, t);
            }
        }

        @Override
        public void onSubscribe(Subscription subscription) {
            requireNonNull(subscription, "subscription");
            if (upstream != null) {
                subscription.cancel();
                return;
            }
            upstream = subscription;
            if (cancelCalled) {
                subscription.cancel();
                return;
            }
        }

        protected void onComplete0(Subscriber<? super HttpData> downstream) {
            downstream.onComplete();
        }

        protected void onError0(Throwable cause, Subscriber<? super HttpData> downstream) {
            downstream.onError(cause);
            this.downstream = NoopSubscriber.get();
        }
    }
}
