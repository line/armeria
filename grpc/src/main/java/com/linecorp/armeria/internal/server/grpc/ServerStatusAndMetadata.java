/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.internal.server.grpc;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.grpc.StatusAndMetadata;

import io.grpc.Metadata;
import io.grpc.Status;

public final class ServerStatusAndMetadata extends StatusAndMetadata {

    private boolean shouldCancel;
    // Set true if response content log should be written
    private boolean setResponseContent;

    public ServerStatusAndMetadata(Status status, @Nullable Metadata metadata, boolean setResponseContent) {
        super(status, metadata);
        this.setResponseContent = setResponseContent;
    }

    public ServerStatusAndMetadata(Status status, @Nullable Metadata metadata, boolean setResponseContent,
                                   boolean shouldCancel) {
        super(status, metadata);
        this.setResponseContent = setResponseContent;
        this.shouldCancel = shouldCancel;
    }

    public boolean isShouldCancel() {
        return shouldCancel;
    }

    /**
     * Tries to mark whether the call should be cancelled. If a call path
     * has already set the status to be cancelled, subsequent calls have no effect.
     */
    public void shouldCancel() {
        shouldCancel = true;
    }

    public void setResponseContent(boolean setResponseContent) {
        this.setResponseContent = setResponseContent;
    }

    public boolean setResponseContent() {
        return setResponseContent;
    }

    public ServerStatusAndMetadata withStatus(Status status) {
        return new ServerStatusAndMetadata(status, metadata(), setResponseContent(), isShouldCancel());
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("status", status())
                          .add("metadata", metadata())
                          .add("shouldCancel", shouldCancel)
                          .add("setResponseContent", setResponseContent)
                          .toString();
    }
}
