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

package com.linecorp.armeria.spring.web.reactive;

import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.core.env.Environment;
import org.springframework.http.server.reactive.HttpHandler;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.util.Exceptions;

class RetryableArmeriaReactiveWebServerFactory extends ArmeriaReactiveWebServerFactory {

    private static final Logger logger =
            LoggerFactory.getLogger(RetryableArmeriaReactiveWebServerFactory.class);

    private static final int MAX_ATTEMPTS = 8;

    RetryableArmeriaReactiveWebServerFactory(ConfigurableListableBeanFactory beanFactory,
                                             Environment environment) {
        super(beanFactory, environment);
    }

    @Override
    public WebServer getWebServer(HttpHandler httpHandler) {
        return new RetryableWebServer(super.getWebServer(httpHandler), MAX_ATTEMPTS);
    }

    private static class RetryableWebServer implements WebServer {

        private final WebServer delegate;
        private final int maxAttempts;
        private final Backoff backoff;

        RetryableWebServer(WebServer delegate, int maxAttempts) {
            this.delegate = delegate;
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
        public int getPort() {
            return delegate.getPort();
        }
    }
}
