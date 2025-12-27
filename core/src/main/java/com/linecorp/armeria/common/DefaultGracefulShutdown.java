/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.common;

import java.time.Duration;
import java.util.Objects;

import com.google.common.base.MoreObjects;

final class DefaultGracefulShutdown implements GracefulShutdown {

    private final Duration quietPeriod;
    private final Duration timeout;

    DefaultGracefulShutdown(Duration quietPeriod, Duration timeout) {
        this.quietPeriod = quietPeriod;
        this.timeout = timeout;
    }

    @Override
    public Duration quietPeriod() {
        return quietPeriod;
    }

    @Override
    public Duration timeout() {
        return timeout;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DefaultGracefulShutdown)) {
            return false;
        }
        final DefaultGracefulShutdown that = (DefaultGracefulShutdown) o;
        return quietPeriod.equals(that.quietPeriod) &&
               timeout.equals(that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(quietPeriod, timeout);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("quietPeriod", quietPeriod)
                          .add("timeout", timeout)
                          .toString();
    }
}
