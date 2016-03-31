/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.Method;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientCodec;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.DecoratingRemoteInvoker;
import com.linecorp.armeria.client.RemoteInvoker;

import io.netty.channel.EventLoop;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;

/**
 * A {@link DecoratingRemoteInvoker} that deals with failures of remote invocation based on circuit breaker
 * pattern.
 */
class CircuitBreakerRemoteInvoker extends DecoratingRemoteInvoker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerRemoteInvoker.class);

    private final CircuitBreakerMapping mapping;

    /**
     * Creates a new instance that decorates the given {@link RemoteInvoker}.
     */
    CircuitBreakerRemoteInvoker(RemoteInvoker delegate, CircuitBreakerMapping mapping) {
        super(delegate);
        this.mapping = requireNonNull(mapping, "mapping");
    }

    @Override
    public <T> Future<T> invoke(EventLoop eventLoop, URI uri, ClientOptions options, ClientCodec codec,
                                Method method, Object[] args) throws Exception {

        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(eventLoop, uri, options, codec, method, args);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return delegate().invoke(eventLoop, uri, options, codec, method, args);
        }

        if (circuitBreaker.canRequest()) {
            final Future<T> resultFut = delegate().invoke(eventLoop, uri, options, codec, method, args);
            resultFut.addListener(future -> {
                if (future.isSuccess()) {
                    // reports success event
                    circuitBreaker.onSuccess();
                } else {
                    circuitBreaker.onFailure(future.cause());
                }
            });
            return resultFut;
        } else {
            // the circuit is tripped

            // prepares a failed resultPromise
            final Promise<T> resultPromise = eventLoop.newPromise();
            resultPromise.setFailure(new FailFastException(circuitBreaker));
            codec.prepareRequest(method, args, resultPromise);

            // returns immediately without calling succeeding remote invokers
            return resultPromise;
        }
    }

}
