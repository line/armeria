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

package com.linecorp.armeria.unsafe;

import static java.util.Objects.requireNonNull;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.AbstractHttpData;
import com.linecorp.armeria.common.HttpData;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.ByteBufInputStream;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

/**
 * A {@link HttpData} that is backed by a pooled {@link ByteBuf} for optimizing certain internal use cases. Not
 * for general use.
 *
 * <h3>What are pooled buffers?</h3>
 *
 * <p>The buffer backing a {@link ByteBufHttpData} is a pooled buffer - this means that it does not use normal
 * Java garbage collection, and instead uses manual memory management using a reference count, similar to
 * some constructs in languages like C++. Manual memory management is more fragile and not idiomatic in Java -
 * you should only use this class in performance-sensitive situations and after being ready to deal with these
 * very hard-to-debug issues.
 *
 * <p>You may interact with {@link ByteBufHttpData} when using methods that return pooled objects, such as
 * {@link com.linecorp.armeria.common.HttpResponse#aggregateWithPooledObjects(ByteBufAllocator)}. If you don't
 * use such methods, you will never see a {@link ByteBufHttpData} and don't need to read further.
 *
 * <h3>Impact of pooled buffers</h3>
 *
 * <p>Any time you receive a {@link ByteBufHttpData} it will have a single reference that must be released -
 * failure to release the reference will result in a memory leak and poor performance. You must make sure to do
 * this by calling {@link HttpData#close()}, usually in a try-with-resources structure to avoid side effects.
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
 * If called after the block, or a manual call to {@link HttpData#close}, these methods will fail or corrupt
 * data.
 *
 * <h3>Writing code compatible with both pooled and unpooled objects</h3>
 *
 * <p>When requesting pooled objects, it is not guaranteed that all {@link HttpData} are actually pooled, e.g.,
 * a decorator may not understand pooled objects, so it will copy objects onto the Java heap. Code
 * will still be able to operate on any type of {@link HttpData} as long as it calls
 * {@link HttpData#close()} and uses methods like {@link HttpData#toInputStream()},
 * {@link HttpData#toByteBuffer()}, or {@link HttpData#toStringUtf8()} to access the content. The above example
 * works well regardless of whether the {@link HttpData} is pooled or not.
 *
 * <h3>Even more advanced usage</h3>
 *
 * <p>In some cases, you may want to access the {@link ByteBuf} held by this {@link ByteBufHttpData}. This will
 * generally be used with a fallback to wrapping an unpooled {@link HttpData}, e.g., <pre>{@code
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
public class ByteBufHttpData extends AbstractHttpData implements ByteBufHolder {

    private static final AtomicIntegerFieldUpdater<ByteBufHttpData> closedUpdater =
            AtomicIntegerFieldUpdater.newUpdater(ByteBufHttpData.class, "closed");

    private final ByteBuf buf;
    private final boolean endOfStream;
    private final int length;

    @SuppressWarnings("FieldMayBeFinal") // Updated via `closedUpdater`
    private volatile int closed;

    /**
     * Constructs a new {@link ByteBufHttpData}. Ownership of {@code buf} is taken by this
     * {@link ByteBufHttpData}, which must not be mutated anymore.
     */
    public ByteBufHttpData(ByteBuf buf, boolean endOfStream) {
        length = requireNonNull(buf, "buf").readableBytes();
        if (length != 0) {
            this.buf = buf;
        } else {
            buf.release();
            this.buf = Unpooled.EMPTY_BUFFER;
        }
        this.endOfStream = endOfStream;
    }

    @Override
    public boolean isEndOfStream() {
        return endOfStream;
    }

    @Override
    public byte[] array() {
        if (buf.hasArray() && buf.arrayOffset() == 0 && buf.array().length == length) {
            return buf.array();
        } else {
            return ByteBufUtil.getBytes(buf);
        }
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public boolean isEmpty() {
        buf.touch();
        return super.isEmpty();
    }

    @Override
    public int refCnt() {
        return buf.refCnt();
    }

    @Override
    public ByteBufHttpData retain() {
        buf.retain();
        return this;
    }

    @Override
    public ByteBufHttpData retain(int increment) {
        buf.retain(increment);
        return this;
    }

    @Override
    public ByteBufHttpData touch() {
        buf.touch();
        return this;
    }

    @Override
    public ByteBufHttpData touch(Object hint) {
        buf.touch(hint);
        return this;
    }

    @Override
    public boolean release() {
        return buf.release();
    }

    @Override
    public boolean release(int decrement) {
        return buf.release(decrement);
    }

    @Override
    public ByteBuf content() {
        buf.touch();
        return buf;
    }

    @Override
    public ByteBufHttpData copy() {
        return new ByteBufHttpData(buf.copy(), endOfStream);
    }

    @Override
    public ByteBufHttpData duplicate() {
        return new ByteBufHttpData(buf.duplicate(), endOfStream);
    }

    @Override
    public ByteBufHttpData retainedDuplicate() {
        return new ByteBufHttpData(buf.retainedDuplicate(), endOfStream);
    }

    @Override
    public ByteBufHttpData replace(ByteBuf content) {
        requireNonNull(content, "content");
        content.touch();
        return new ByteBufHttpData(content, endOfStream);
    }

    @Override
    protected byte getByte(int index) {
        return buf.getByte(index);
    }

    @Override
    public String toString(Charset charset) {
        return buf.toString(charset);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("buf", buf.toString()).toString();
    }

    @Override
    public InputStream toInputStream() {
        return new ByteBufInputStream(buf.duplicate(), false);
    }

    @Override
    public ByteBuffer toByteBuffer() {
        return buf.nioBuffer();
    }

    @Override
    public void close() {
        if (!closedUpdater.compareAndSet(this, 0, 1)) {
            return;
        }
        release();
    }
}
