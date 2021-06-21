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
package com.linecorp.armeria.unsafe;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.server.file.HttpFile;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

/**
 * Utility class that provides ways to create a pooled {@link HttpData} and manage its life cycle.
 *
 * <p><b>Warning</b>: Using a pooled {@link HttpData} is very advanced and can open up much more complicated
 * management of a reference counted {@link ByteBuf}. You should only ever do this if you are very comfortable
 * with Netty. It is recommended to also read through
 * <a href="https://netty.io/wiki/reference-counted-objects.html">Reference counted objects</a>
 * for more information on pooled objects.</p>
 *
 * <h2>What is a pooled {@link HttpData}?</h2>
 *
 * <p>A pooled {@link HttpData} is a special variant of {@link HttpData} whose {@link HttpData#isPooled()}
 * returns {@code true}. It's usually created via {@link HttpData#wrap(ByteBuf)} by wrapping an existing
 * {@link ByteBuf}. It can appear when you consume data using the operations such as:
 * <ul>
 *   <li>{@link StreamMessage#subscribe(Subscriber, SubscriptionOption...)} with
 *       {@link SubscriptionOption#WITH_POOLED_OBJECTS}</li>
 *   <li>{@link HttpRequest#aggregateWithPooledObjects(ByteBufAllocator)}</li>
 *   <li>{@link HttpResponse#aggregateWithPooledObjects(ByteBufAllocator)}</li>
 *   <li>{@link HttpFile#aggregateWithPooledObjects(Executor, ByteBufAllocator)}</li>
 * </ul>
 *
 * <p>To put it another way, you'll <b>never</b> see a pooled {@link HttpData} if you did not use such
 * operations. You can ignore the rest of this section if that's the case.</p>
 *
 * <p>Any time you receive a pooled {@link HttpData}, it will have an underlying {@link ByteBuf} that must be
 * released - failure to release the {@link ByteBuf} will result in a memory leak and poor performance.
 * You must make sure to do this by calling {@link HttpData#close()}, usually in a try-with-resources structure
 * to avoid side effects, e.g.
 * <pre>{@code
 * HttpResponse res = client.get("/");
 * res.aggregateWithPooledObjects(ctx.alloc(), ctx.executor())
 *    .thenApply(aggResp -> {
 *        // try-with-resources here ensures the content is released
 *        // if it is a pooled HttpData, or otherwise it's no-op.
 *        try (HttpData content = aggResp.content()) {
 *            if (!aggResp.status().equals(HttpStatus.OK)) {
 *                throw new IllegalStateException("Bad response");
 *            }
 *            try {
 *                return OBJECT_MAPPER.readValue(content.toInputStream(), Foo.class);
 *            } catch (IOException e) {
 *                throw new IllegalArgumentException("Bad JSON: " + content.toStringUtf8());
 *            }
 *        }
 *    });
 * }</pre>
 *
 * <p>In the above example, it is the initial {@code try (HttpData content = ...)} that ensures the data
 * is released. Calls to methods on {@link HttpData} will all work and can be called any number of times within
 * this block. If called after the block or a manual call to {@link HttpData#close()}, these methods will fail
 * or corrupt data.</p>
 */
@UnstableApi
public final class PooledObjects {

    /**
     * Closes the given pooled {@link HttpData}. Does nothing if it's not a pooled {@link HttpData}.
     *
     * @param obj maybe an {@link HttpData} to close
     */
    public static void close(Object obj) {
        if (obj instanceof HttpData) {
            ((HttpData) obj).close();
        }
    }

    /**
     * Calls {@link ByteBuf#touch(Object)} on the specified {@link HttpData}'s underlying {@link ByteBuf}.
     * Uses the specified {@link HttpData} as a hint. Does nothing if it's not a pooled {@link HttpData}.
     *
     * @param obj maybe a pooled {@link HttpData} to touch its underlying {@link ByteBuf}
     */
    public static <T> T touch(T obj) {
        return touch(obj, obj);
    }

    /**
     * Calls {@link ByteBuf#touch(Object)} on the specified {@link HttpData}'s underlying {@link ByteBuf}.
     * Does nothing if it's not a pooled {@link HttpData}.
     *
     * @param obj maybe a pooled {@link HttpData} to touch its underlying {@link ByteBuf}
     * @param hint the hint to specify when calling {@link ByteBuf#touch(Object)}
     */
    public static <T> T touch(T obj, @Nullable Object hint) {
        if (obj instanceof HttpData) {
            ((HttpData) obj).touch(hint);
        }
        return obj;
    }

    /**
     * Creates an unpooled copy of the given {@link HttpData} and closes the given {@link HttpData}.
     * Returns the given object as is if it's not a pooled {@link HttpData}. This method is useful when
     * you need to pass your pooled {@link HttpData} instances to the third party who is not capable of
     * handling pooled {@link HttpData}.
     *
     * @param obj maybe an {@link HttpData} to copy
     */
    public static <T> T copyAndClose(T obj) {
        if (obj instanceof HttpData) {
            final HttpData data = (HttpData) obj;
            if (data.isPooled()) {
                try {
                    @SuppressWarnings("unchecked")
                    final T copy = (T) HttpData.wrap(data.array()).withEndOfStream(data.isEndOfStream());
                    return copy;
                } finally {
                    data.close();
                }
            }
        }
        return obj;
    }

    private PooledObjects() {}
}
