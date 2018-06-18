/*
 * Copyright 2018 LINE Corporation
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

package com.linecorp.armeria.it.tracing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import zipkin2.Span;
import zipkin2.reporter.Reporter;

class ReporterImpl implements Reporter<Span> {

    private final BlockingQueue<Span> spans = new LinkedBlockingQueue<>();

    @Override
    public void report(Span span) {
        spans.add(span);
    }

    BlockingQueue<Span> getSpans() {
        return spans;
    }

    Span[] take(int numSpans) throws InterruptedException {
        final List<Span> taken = new ArrayList<>();
        while (taken.size() < numSpans) {
            taken.add(spans.take());
        }

        // Reverse the collected spans to sort the spans by request time.
        Collections.reverse(taken);
        return taken.toArray(new Span[numSpans]);
    }
}
