/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.common.http;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.MoreObjects;

final class HttpResponseAggregator extends HttpMessageAggregator {

    private List<HttpHeaders> informationals;
    private HttpHeaders headers;
    private HttpHeaders trailingHeaders;

    HttpResponseAggregator(CompletableFuture<AggregatedHttpMessage> future) {
        super(future);
        trailingHeaders = HttpHeaders.EMPTY_HEADERS;
    }

    @Override
    public void onNext(HttpObject o) {
        if (o instanceof HttpHeaders) {
            final HttpHeaders headers = (HttpHeaders) o;
            if (headers.status().codeClass() == HttpStatusClass.INFORMATIONAL) {
                if (informationals == null) {
                    informationals = new ArrayList<>(2);
                }
                informationals.add(headers);
            } else if (this.headers == null) {
                this.headers = headers;
            } else {
                trailingHeaders = headers;
            }
        } else {
            add((HttpData) o);
        }
    }

    @Override
    public void onError(Throwable t) {
        clear();
        future().completeExceptionally(t);
    }

    @Override
    protected void doClear() {
        headers = null;
        trailingHeaders = null;
    }

    @Override
    public void onComplete() {
        final HttpData content = finish();
        future().complete(AggregatedHttpMessage.of(
                MoreObjects.firstNonNull(informationals, Collections.emptyList()),
                headers, content, trailingHeaders));
    }
}
