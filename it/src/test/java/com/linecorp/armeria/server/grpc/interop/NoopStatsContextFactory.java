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
/*
 * Copyright 2016, Google Inc. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 *    * Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following disclaimer
 * in the documentation and/or other materials provided with the
 * distribution.
 *
 *    * Neither the name of Google Inc. nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.linecorp.armeria.server.grpc.interop;

import java.io.InputStream;
import java.io.OutputStream;

import com.google.instrumentation.stats.MeasurementMap;
import com.google.instrumentation.stats.StatsContext;
import com.google.instrumentation.stats.StatsContextFactory;
import com.google.instrumentation.stats.TagKey;
import com.google.instrumentation.stats.TagValue;

final class NoopStatsContextFactory extends StatsContextFactory {

    private static final StatsContext DEFAULT_CONTEXT = new NoopStatsContext();
    private static final StatsContext.Builder BUILDER = new NoopContextBuilder();

    public static final StatsContextFactory INSTANCE = new NoopStatsContextFactory();

    private NoopStatsContextFactory() {
    }

    @Override
    public StatsContext deserialize(InputStream is) {
        return DEFAULT_CONTEXT;
    }

    @Override
    public StatsContext getDefault() {
        return DEFAULT_CONTEXT;
    }

    private static class NoopStatsContext extends StatsContext {
        @Override
        public Builder builder() {
            return BUILDER;
        }

        @Override
        public StatsContext record(MeasurementMap metrics) {
            return DEFAULT_CONTEXT;
        }

        @Override
        public void serialize(OutputStream os) {}
    }

    private static class NoopContextBuilder extends StatsContext.Builder {
        @Override
        public StatsContext.Builder set(TagKey key, TagValue value) {
            return this;
        }

        @Override
        public StatsContext build() {
            return DEFAULT_CONTEXT;
        }
    }
}
