/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.spring;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.Server;

/**
 * A {@link SmartLifecycle} which retries to start the {@link Server} up to {@code maxAttempts}.
 * This is useful for testing that needs to bind a server to a random port number obtained in advance.
 */
public final class RetryableArmeriaServerGracefulShutdownLifecycle implements ArmeriaServerSmartLifecycle {

    private static final Logger logger =
            LoggerFactory.getLogger(RetryableArmeriaServerGracefulShutdownLifecycle.class);

    private final SmartLifecycle delegate;
    private final int maxAttempts;
    private final Backoff backoff;

    public RetryableArmeriaServerGracefulShutdownLifecycle(Server server, int maxAttempts) {
        delegate = new ArmeriaServerGracefulShutdownLifecycle(server);
        this.maxAttempts = maxAttempts;
        backoff = Backoff.ofDefault();
    }

    @Override
    public void start() {
        Throwable caughtException = null;
        for (int i = 1; i <= maxAttempts; i++) {
            try {
                delegate.start();
                return;
            } catch (Exception ex) {
                caughtException = Exceptions.peel(ex);
                if (i < maxAttempts) {
                    final long delayMillis = backoff.nextDelayMillis(i);
                    logger.debug("{}; retrying in {} ms (attempts so far: {})",
                                 ex.getMessage(), delayMillis, i, ex);
                    Uninterruptibles.sleepUninterruptibly(delayMillis, TimeUnit.MILLISECONDS);
                }
            }
        }

        assert caughtException != null;
        Exceptions.throwUnsafely(caughtException);
    }

    @Override
    public void stop() {
        delegate.stop();
    }

    @Override
    public void stop(Runnable callback) {
        delegate.stop(callback);
    }

    @Override
    public boolean isAutoStartup() {
        return delegate.isAutoStartup();
    }

    @Override
    public int getPhase() {
        return delegate.getPhase();
    }

    @Override
    public boolean isRunning() {
        return delegate.isRunning();
    }
}
