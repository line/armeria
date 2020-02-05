/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.internal.common.util;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.openjdk.jmh.annotations.Benchmark;

public class HttpTimestampSupplierBenchmark {

    @Benchmark
    public String cached() {
        return HttpTimestampSupplier.currentTime();
    }

    @Benchmark
    public String notCached() {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(Clock.systemUTC()));
    }
}
