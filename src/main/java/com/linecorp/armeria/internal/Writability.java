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

package com.linecorp.armeria.internal;

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.MoreObjects;

public final class Writability extends AtomicInteger {

    private static final long serialVersionUID = 420503276551000218L;

    private final int highWatermark;
    private final int lowWatermark;
    private volatile boolean writable = true;

    public Writability() {
        this(128 * 1024, 64 * 1024);
    }

    public Writability(int highWatermark, int lowWatermark) {
        this.highWatermark = highWatermark;
        this.lowWatermark = lowWatermark;
    }

    public boolean inc(int amount) {
        final int newValue = addAndGet(amount);
        if (newValue > highWatermark) {
            return writable = false;
        } else {
            return writable;
        }
    }

    public boolean dec(int amount) {
        final int newValue = addAndGet(-amount);
        if (newValue < lowWatermark) {
            return writable = true;
        } else {
            return writable;
        }
    }

    public boolean isWritable() {
        return writable;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("level", get())
                          .add("watermarks", highWatermark + "/" + lowWatermark)
                          .toString();
    }
}
