/*
 * Copyright 2016 LINE Corporation
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

package com.linecorp.armeria.common.tracing;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedTransferQueue;

import zipkin2.Span;
import zipkin2.reporter.Reporter;

public final class SpanCollectingReporter implements Reporter<Span> {

    private final BlockingQueue<Span> spans = new LinkedTransferQueue<>();

    @Override
    public void report(Span span) {
        spans.add(span);
    }

    public BlockingQueue<Span> spans() {
        return spans;
    }
}
