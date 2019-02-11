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
package com.linecorp.armeria.server.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.junit.Test;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;

public class CachingHttpFileTest {

    private static final Executor executor = MoreExecutors.directExecutor();
    private static final ByteBufAllocator alloc = UnpooledByteBufAllocator.DEFAULT;

    /**
     * Makes sure zero overhead when {@code maxCachingLength} is {@code 0}.
     */
    @Test
    public void disabledCache() {
        final HttpFile uncached = mock(HttpFile.class);
        assertThat(HttpFile.ofCached(uncached, 0)).isSameAs(uncached);
    }

    /**
     * Makes sure a non-existent file is handled as expected.
     */
    @Test
    public void nonExistentFile() throws Exception {
        final HttpFile cached = HttpFile.ofCached(HttpFile.nonExistent(), Integer.MAX_VALUE);
        assertThat(cached.readAttributes()).isNull();
        assertThat(cached.readHeaders()).isNull();
        assertThat(cached.read(executor, alloc)).isNull();
        assertThat(cached.aggregate(executor).join()).isSameAs(HttpFile.nonExistent());
        assertThat(cached.aggregateWithPooledObjects(executor, alloc).join()).isSameAs(HttpFile.nonExistent());

        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        assertThat(cached.asService().serve(ctx, ctx.request()).aggregate().join().status())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    /**
     * Makes sure a regular file is handled as expected, including proper cache invalidation.
     */
    @Test
    public void existentFile() throws Exception {
        final HttpFileAttributes attrs = new HttpFileAttributes(3, 0);
        final HttpHeaders headers = HttpHeaders.of(200);
        final HttpFile uncached = mock(HttpFile.class);
        when(uncached.readAttributes()).thenReturn(attrs);
        when(uncached.readHeaders()).thenReturn(headers);
        when(uncached.read(any(), any())).thenAnswer(invocation -> HttpResponse.of("foo"));
        when(uncached.aggregate(any())).thenAnswer(
                invocation -> CompletableFuture.completedFuture(HttpFile.of(HttpData.ofUtf8("foo"), 0)));

        final HttpFile cached = HttpFile.ofCached(uncached, 3);

        // Ensure readAttributes() is not cached.
        assertThat(cached.readAttributes()).isSameAs(attrs);
        verify(uncached, times(1)).readAttributes();
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        assertThat(cached.readAttributes()).isSameAs(attrs);
        verify(uncached, times(1)).readAttributes();
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // Ensure readHeaders() is not cached.
        assertThat(cached.readHeaders()).isSameAs(headers);
        verify(uncached, times(1)).readHeaders();
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        assertThat(cached.readHeaders()).isSameAs(headers);
        verify(uncached, times(1)).readHeaders();
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // First read() should trigger uncached.aggregate().
        HttpResponse res = cached.read(executor, alloc);
        assertThat(res).isNotNull();
        assertThat(res.aggregate().join().contentUtf8()).isEqualTo("foo");
        verify(uncached, times(1)).readAttributes();
        verify(uncached, times(1)).aggregate(executor);
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // Second read() should not trigger uncached.aggregate().
        res = cached.read(executor, alloc);
        assertThat(res).isNotNull();
        assertThat(res.aggregate().join().contentUtf8()).isEqualTo("foo");
        verify(uncached, times(1)).readAttributes();
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // Update the uncached file's attributes to invalidate the cache.
        final HttpFileAttributes newAttrs = new HttpFileAttributes(3, 1);
        when(uncached.readAttributes()).thenReturn(newAttrs);
        when(uncached.aggregate(any())).thenAnswer(
                invocation -> CompletableFuture.completedFuture(HttpFile.of(HttpData.ofUtf8("bar"), 1)));

        // Make sure read() invalidates the cache and triggers uncached.aggregate().
        res = cached.read(executor, alloc);
        assertThat(res).isNotNull();
        assertThat(res.aggregate().join().contentUtf8()).isEqualTo("bar");
        verify(uncached, times(1)).readAttributes();
        verify(uncached, times(1)).aggregate(executor);
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);
    }

    /**
     * Makes sure a large file is not cached.
     */
    @Test
    public void largeFile() throws Exception {
        final HttpFileAttributes attrs = new HttpFileAttributes(5, 0);
        final HttpHeaders headers = HttpHeaders.of(200);
        final HttpResponse res = HttpResponse.of("large");
        final CompletableFuture<AggregatedHttpFile> aggregated =
                CompletableFuture.completedFuture(HttpFile.of(HttpData.ofUtf8("large"), 0));
        final CompletableFuture<AggregatedHttpFile> aggregatedWithPooledObjs =
                CompletableFuture.completedFuture(HttpFile.of(HttpData.ofUtf8("large"), 0));
        final HttpFile uncached = mock(HttpFile.class);
        when(uncached.readAttributes()).thenReturn(attrs);
        when(uncached.readHeaders()).thenReturn(headers);
        when(uncached.read(any(), any())).thenReturn(res);
        when(uncached.aggregate(any())).thenReturn(aggregated);
        when(uncached.aggregateWithPooledObjects(any(), any())).thenReturn(aggregatedWithPooledObjs);

        final HttpFile cached = HttpFile.ofCached(uncached, 4);

        // read() should be delegated to 'uncached'.
        assertThat(cached.read(executor, alloc)).isSameAs(res);
        verify(uncached, times(1)).readAttributes();
        verify(uncached, times(1)).read(executor, alloc);
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // aggregate() should be delegated to 'uncached'.
        assertThat(cached.aggregate(executor)).isSameAs(aggregated);
        verify(uncached, times(1)).readAttributes();
        verify(uncached, times(1)).aggregate(executor);
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);

        // aggregateWithPooledObjects() should be delegated to 'uncached'.
        assertThat(cached.aggregateWithPooledObjects(executor, alloc)).isSameAs(aggregatedWithPooledObjs);
        verify(uncached, times(1)).readAttributes();
        verify(uncached, times(1)).aggregateWithPooledObjects(executor, alloc);
        verifyNoMoreInteractions(uncached);
        clearInvocations(uncached);
    }
}
