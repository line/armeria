/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.client.retry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;

final class RetryLimiters {

    static final class CatchingRetryLimiter implements RetryLimiter {

        private static final Logger logger = LoggerFactory.getLogger(CatchingRetryLimiter.class);

        private final RetryLimiter delegate;

        CatchingRetryLimiter(RetryLimiter delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean shouldRetry(ClientRequestContext ctx) {
            try {
                return delegate.shouldRetry(ctx);
            } catch (Exception e) {
                logger.warn("Unexpected error when invoking RetryLimiter.shouldRetry: ", e);
                return false;
            }
        }

        @Override
        public void handleDecision(ClientRequestContext ctx, RetryDecision decision) {
            try {
                delegate.handleDecision(ctx, decision);
            } catch (Exception e) {
                logger.warn("Unexpected error when invoking RetryLimiter.handleDecision: ", e);
            }
        }
    }

    static final class AlwaysRetryLimiter implements RetryLimiter {

        static final RetryLimiter INSTANCE = new AlwaysRetryLimiter();

        @Override
        public boolean shouldRetry(ClientRequestContext ctx) {
            return true;
        }
    }
}
