/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.common.unsafe;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.client.unsafe.PooledWebClient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.Unpooled;

/**
 * A {@link HttpData} that is backed by a pooled {@link ByteBuf} for optimizing certain internal use cases. Not
 * for general use.
 *
 * <h3>What are pooled buffers?</h3>
 *
 * <p>The buffer backing a {@link PooledHttpData} is a pooled buffer - this means that it does not use normal
 * Java garbage collection, and instead uses manual memory management using a reference count, similar to
 * some constructs in languages like C++. Manual memory management is more fragile and not idiomatic in Java -
 * you should only use this class in performance-sensitive situations and after being ready to deal with these
 * very hard-to-debug issues.
 *
 * <p>You may interact with {@link PooledHttpData} when using objects that return pooled objects, such as
 * {@link PooledWebClient}. If you don't use such objects, you will never see a {@link PooledHttpData} and don't
 * need to read further.
 *
 * <h3>Impact of pooled buffers</h3>
 *
 * <p>Any time you receive a {@link PooledHttpData} it will have a single reference that must be released -
 * failure to release the reference will result in a memory leak and poor performance. You must make sure to do
 * this by calling {@link PooledHttpData#close()}, usually in a try-with-resources structure to avoid side
 * effects.
 *
 * <p>For example, <pre>{@code
 *
 * HttpResponse response = client.get("/");
 * response.aggregateWithPooledObjects(ctx.alloc(), ctx.executor())
 *     .thenApply(aggResp -> {
 *         // try-with-resources here ensures the content is released if it is a ByteBufHttpData, or otherwise
 *         // is a no-op if it is not.
 *         try (HttpData content = aggResp.content()) {
 *             if (!aggResp.status().equals(HttpStatus.OK)) {
 *                 throw new IllegalStateException("Bad response");
 *             }
 *             try {
 *                 return OBJECT_MAPPER.readValue(content.toInputStream(), Foo.class);
 *             } catch (IOException e) {
 *                 throw new IllegalArgumentException("Bad JSON: " + content.toStringUtf8());
 *             }
 *         }
 *     });
 *
 * }</pre>
 *
 * <p>In this example, it is the initial {@code try (HttpData content = ...} that ensures the data is released.
 * Calls to methods on {@link HttpData} will all work and can be called any number of times within this block.
 * If called after the block, or a manual call to {@link PooledHttpData#close}, these methods will fail or
 * corrupt data.
 *
 * <h3>Even more advanced usage</h3>
 *
 * <p>In some cases, you may want to access the {@link ByteBuf} held by this {@link PooledHttpData}. This will
 * generally be used with a fallback to wrapping an unpooled {@link PooledHttpData}, e.g., <pre>{@code
 *
 * final ByteBuf buf;
 * if (data instanceof ByteBufHolder) {
 *     buf = ((ByteBufHolder) data).content();
 * } else {
 *     buf = Unpooled.wrappedBuffer(data.array());
 * }
 *
 * }</pre>
 *
 * <p>Using a {@link ByteBuf} directly is very advanced and can open up much more complicated management of
 * reference count. You should only ever do this if you are very comfortable with Netty.
 *
 * <p>It is recommended to also read through <a href="https://netty.io/wiki/reference-counted-objects.html">
 * Reference counted objects</a> for more information on pooled objects.
 */
public interface PooledHttpData extends HttpData, ByteBufHolder, SafeCloseable {

    /**
     * Converts non-pooled {@link HttpData} into {@link PooledHttpData}.
     */
    static PooledHttpData of(HttpData data) {
        requireNonNull(data, "obj");
        if (data instanceof PooledHttpData) {
            return (PooledHttpData) data;
        }

        return new ByteBufHttpData(Unpooled.wrappedBuffer(data.array()), data.isEndOfStream());
    }

    /**
     * Converts the specified Netty {@link ByteBuf} into an {@link PooledHttpData}. The buffer is not copied;
     * any changes made to it will be visible to {@link PooledHttpData}. The ownership of the buffer is
     * transferred to the {@link HttpData}. If you still need to use it after calling this method, make sure to
     * call {@link ByteBuf#retain()} first.
     *
     * @return a new {@link HttpData}. {@link #empty()} if the readable bytes of {@code buf} is 0.
     */
    static PooledHttpData wrap(ByteBuf buf) {
        requireNonNull(buf, "buf");
        if (!buf.isReadable()) {
            return ByteBufHttpData.EMPTY;
        }
        return new ByteBufHttpData(buf, false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    PooledHttpData withEndOfStream();
}
