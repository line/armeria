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

package com.linecorp.armeria.common;

import java.net.InetAddress;
import java.util.function.Predicate;

import com.linecorp.armeria.common.util.InetAddressPredicates;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;

public final class BaseFlagsProvider implements FlagsProvider {

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public Boolean useOpenSsl() {
        return false;
    }

    @Override
    public Integer maxNumConnections() {
        return 20;
    }

    @Override
    public Integer numCommonBlockingTaskThreads() {
        return 100;
    }

    @Override
    public Long defaultRequestTimeoutMillis() {
        return -10L; //invalid
    }

    @Override
    public String defaultBackoffSpec() {
        return "invalid backoff spec"; //invalid
    }

    @Override
    public Integer defaultMaxTotalAttempts() {
        return 5;
    }

    @Override
    public Long defaultMaxClientConnectionAgeMillis() {
        return 10L;
    }

    @Override
    public Long defaultServerConnectionDrainDurationMicros() {
        return 500L;
    }

    @Override
    public String routeCacheSpec() {
        return "off";
    }

    @Override
    public String headerValueCacheSpec() {
        return "maximumSize=4096,expireAfterAccess=600s";
    }

    @Override
    public Predicate<InetAddress> preferredIpV4Addresses() {
        return InetAddressPredicates.ofCidr("211.111.111.111");
    }

    @Override
    public MeterRegistry meterRegistry() {
        return new CompositeMeterRegistry();
    }
}
