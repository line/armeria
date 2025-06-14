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

class RetrySchedulingException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final Type type;

    enum Type {
        NO_MORE_ATTEMPTS_IN_RETRY("No more attempts available in retry"),
        NO_MORE_ATTEMPTS_IN_BACKOFF("No more attempts available in backoff"),
        DELAY_FROM_BACKOFF_EXCEEDS_RESPONSE_TIMEOUT("Delay from backoff exceeds response timeout"),
        DELAY_FROM_SERVER_EXCEEDS_RESPONSE_TIMEOUT("Delay from server exceeds response timeout"),
        RETRY_TASK_OVERTAKEN("Has earlier retry"),
        RETRY_TASK_CANCELLED("Retry task cancelled without outside of rescheduling.");

        private final String message;

        Type(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    RetrySchedulingException(Type type) {
        super(type.getMessage());
        this.type = type;
    }

    Type getType() {
        return type;
    }
}

