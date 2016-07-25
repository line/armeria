/*
 * Copyright 2015 LINE Corporation
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

package com.linecorp.armeria.server.http.file;

import static java.util.Objects.requireNonNull;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

import com.linecorp.armeria.common.ServiceInvocationContext;
import com.linecorp.armeria.common.util.LruMap;
import com.linecorp.armeria.server.ServiceInvocationHandler;
import com.linecorp.armeria.server.http.file.HttpVfs.Entry;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Promise;

final class HttpFileServiceInvocationHandler implements ServiceInvocationHandler {

    private static final Pattern PROHIBITED_PATH_PATTERN =
            Pattern.compile("(?:[:<>\\|\\?\\*\\\\]|/\\.\\.|\\.\\.$|\\.\\./|//+)");

    private static final String ERROR_MIME_TYPE = "text/plain; charset=UTF-8";
    private static final byte[] CONTENT_NOT_FOUND =
            HttpResponseStatus.NOT_FOUND.toString().getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_METHOD_NOT_ALLOWED =
            HttpResponseStatus.METHOD_NOT_ALLOWED.toString().getBytes(StandardCharsets.UTF_8);

    private final HttpFileServiceConfig config;

    /** An LRU cache map that releases the buffer that contains the cached content. */
    private final Map<String, CachedEntry> cache;

    HttpFileServiceInvocationHandler(HttpFileServiceConfig config) {
        this.config = requireNonNull(config, "config");

        if (config.maxCacheEntries() != 0) {
            cache = Collections.synchronizedMap(new LruMap<String, CachedEntry>(config.maxCacheEntries()) {
                private static final long serialVersionUID = -5517905762044320996L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedEntry> eldest) {
                    final boolean remove = super.removeEldestEntry(eldest);
                    if (remove) {
                        eldest.getValue().destroyContent();
                    }
                    return remove;
                }
            });
        } else {
            cache = null;
        }
    }

    HttpFileServiceConfig config() {
        return config;
    }

    @Override
    public void invoke(ServiceInvocationContext ctx,
                       Executor blockingTaskExecutor, Promise<Object> promise) throws Exception {

        final HttpRequest req = ctx.originalRequest();
        if (req.method() != HttpMethod.GET) {
            respond(ctx, promise, HttpResponseStatus.METHOD_NOT_ALLOWED,
                    0, ERROR_MIME_TYPE, Unpooled.wrappedBuffer(CONTENT_METHOD_NOT_ALLOWED));
            return;
        }

        final String path = normalizePath(ctx.mappedPath());
        if (path == null) {
            respond(ctx, promise, HttpResponseStatus.NOT_FOUND,
                    0, ERROR_MIME_TYPE, Unpooled.wrappedBuffer(CONTENT_NOT_FOUND));
            return;
        }

        Entry entry = getEntry(path);
        long lastModifiedMillis;
        if ((lastModifiedMillis = entry.lastModifiedMillis()) == 0) {
            boolean found = false;
            if (path.charAt(path.length() - 1) == '/') {
                // Try index.html if it was a directory access.
                entry = getEntry(path + "index.html");
                if ((lastModifiedMillis = entry.lastModifiedMillis()) != 0) {
                    found = true;
                }
            }

            if (!found) {
                respond(ctx, promise, HttpResponseStatus.NOT_FOUND,
                        0, ERROR_MIME_TYPE, Unpooled.wrappedBuffer(CONTENT_NOT_FOUND));
                return;
            }
        }

        long ifModifiedSinceMillis = Long.MIN_VALUE;
        try {
            ifModifiedSinceMillis =
                    req.headers().getTimeMillis(HttpHeaderNames.IF_MODIFIED_SINCE, Long.MIN_VALUE);
        } catch (Exception e) {
            // Ignore the ParseException, which is raised on malformed date.
            //noinspection ConstantConditions
            if (!(e instanceof ParseException)) {
                throw e;
            }
        }

        // HTTP-date does not have subsecond-precision; add 999ms to it.
        if (ifModifiedSinceMillis > Long.MAX_VALUE - 999) {
            ifModifiedSinceMillis = Long.MAX_VALUE;
        } else {
            ifModifiedSinceMillis += 999;
        }

        if (lastModifiedMillis < ifModifiedSinceMillis) {
            respond(ctx, promise, HttpResponseStatus.NOT_MODIFIED,
                    lastModifiedMillis, entry.mimeType(), Unpooled.EMPTY_BUFFER);
            return;
        }

        respond(ctx, promise, HttpResponseStatus.OK,
                lastModifiedMillis, entry.mimeType(), entry.readContent(ctx.alloc()));
    }

    private static String normalizePath(String path) {
        // Filter out an empty path or a relative path.
        if (path.isEmpty() || path.charAt(0) != '/') {
            return null;
        }

        // Strip the query string.
        final int queryPos = path.indexOf('?');
        if (queryPos >= 0) {
            path = path.substring(0, queryPos);
        }

        try {
            path = URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException ignored) {
            // Malformed URL
            return null;
        } catch (UnsupportedEncodingException e) {
            // Should never happen
            throw new Error(e);
        }

        // Reject the prohibited patterns.
        if (PROHIBITED_PATH_PATTERN.matcher(path).find()) {
            return null;
        }

        return path;
    }

    private static void respond(
            ServiceInvocationContext ctx, Promise<Object> promise,
            HttpResponseStatus status, long lastModifiedMillis, String contentType, ByteBuf content) {

        final FullHttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
        if (lastModifiedMillis != 0) {
            res.headers().set(HttpHeaderNames.LAST_MODIFIED, new Date(lastModifiedMillis));
        }

        if (contentType != null) {
            res.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
        }

        ctx.resolvePromise(promise, res);
    }

    private Entry getEntry(String path) {
        assert path != null;

        if (cache == null) {
            return config.vfs().get(path);
        }

        CachedEntry e = cache.get(path);
        if (e != null) {
            return e;
        }

        e = new CachedEntry(config.vfs().get(path), config.maxCacheEntrySizeBytes());
        cache.put(path, e);
        return e;
    }

    private static final class CachedEntry implements Entry {

        private final Entry e;
        private final int maxCacheEntrySizeBytes;
        private ByteBuf cachedContent;
        private volatile long cachedLastModifiedMillis;

        CachedEntry(Entry e, int maxCacheEntrySizeBytes) {
            this.e = e;
            this.maxCacheEntrySizeBytes = maxCacheEntrySizeBytes;
            cachedLastModifiedMillis = e.lastModifiedMillis();
        }

        @Override
        public String mimeType() {
            return e.mimeType();
        }

        @Override
        public long lastModifiedMillis() {
            final long newLastModifiedMillis = e.lastModifiedMillis();
            if (newLastModifiedMillis != cachedLastModifiedMillis) {
                cachedLastModifiedMillis = newLastModifiedMillis;
                destroyContent();
            }

            return newLastModifiedMillis;
        }

        @Override
        public synchronized ByteBuf readContent(ByteBufAllocator alloc) throws IOException {
            if (cachedContent == null) {
                final ByteBuf newContent = e.readContent(alloc);
                if (newContent.readableBytes() > maxCacheEntrySizeBytes) {
                    // Do not cache if the content is too large.
                    return newContent;
                }
                cachedContent = newContent;
            }

            return cachedContent.duplicate().retain();
        }

        synchronized void destroyContent() {
            if (cachedContent != null) {
                cachedContent.release();
                cachedContent = null;
            }
        }

        @Override
        public String toString() {
            return e.toString();
        }
    }
}
