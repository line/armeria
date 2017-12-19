/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.DisableOnDebug;
import org.junit.rules.TestRule;
import org.junit.rules.Timeout;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.AggregatedHttpMessage;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseWriter;
import com.linecorp.armeria.common.stream.AbortedStreamException;

@RunWith(Parameterized.class)
public class DefaultHttpResponseTest {

    @Rule
    public TestRule globalTimeout = new DisableOnDebug(new Timeout(10, TimeUnit.SECONDS));

    @Parameters(name = "{index}: executorSpecified={0}")
    public static Collection<Boolean> parameters() {
        return ImmutableList.of(true, false);
    }

    private final boolean executorSpecified;

    public DefaultHttpResponseTest(boolean executorSpecified) {
        this.executorSpecified = executorSpecified;
    }

    /**
     * The aggregation future must be completed even if the response being aggregated has been aborted.
     */
    @Test
    public void abortedAggregation() {
        final Thread mainThread = Thread.currentThread();
        final HttpResponseWriter res = HttpResponse.streaming();
        final CompletableFuture<AggregatedHttpMessage> future;

        // Practically same execution, but we need to test the both case due to code duplication.
        if (executorSpecified) {
            future = res.aggregate(CommonPools.workerGroup().next());
        } else {
            future = res.aggregate();
        }

        final AtomicReference<Thread> callbackThread = new AtomicReference<>();

        assertThatThrownBy(() -> {
            final CompletableFuture<AggregatedHttpMessage> f =
                    future.whenComplete((unused, cause) -> callbackThread.set(Thread.currentThread()));
            res.abort();
            f.join();
        }).hasCauseInstanceOf(AbortedStreamException.class);

        assertThat(callbackThread.get()).isNotSameAs(mainThread);
    }
}
