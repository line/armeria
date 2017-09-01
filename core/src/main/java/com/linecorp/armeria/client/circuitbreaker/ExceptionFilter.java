/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.client.circuitbreaker;

/**
 * A filter that decides whether circuit breaker should deal with a given error raised by remote service.
 */
@FunctionalInterface
public interface ExceptionFilter {

    /**
     * Decides whether the given error should be dealt with circuit breaker.
     *
     * @param throwable The error raised by remote service
     * @return {@code true} if the error should be dealt with circuit breaker
     */
    boolean shouldDealWith(Throwable throwable) throws Exception;
}
