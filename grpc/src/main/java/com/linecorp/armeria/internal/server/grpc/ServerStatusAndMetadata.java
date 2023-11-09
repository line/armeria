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

    // true if `ServerCall.close()` is triggered by the client
    private final boolean cancelled;
    // Set true if response trailers are successfully written
    private boolean completed = true;
    // Set true if response content is written
    private boolean setResponseContent;

    public ServerStatusAndMetadata(Status status, @Nullable Metadata metadata, boolean cancelled) {
        super(status, metadata);
        this.cancelled = cancelled;
    }

    public ServerStatusAndMetadata(Status status, @Nullable Metadata metadata, boolean cancelled,
                                   boolean completed, boolean setResponseContent) {
        super(status, metadata);
        this.cancelled = cancelled;
        this.completed = completed;
        this.setResponseContent = setResponseContent;
    }

    public void completed(boolean completed) {
        this.completed = completed;
    }

    public boolean completed() {
        return completed;
    }

    public void setResponseContent(boolean setResponseContent) {
        this.setResponseContent = setResponseContent;
    }

    public boolean setResponseContent() {
        return setResponseContent;
    }

    /**
     * Overrides the default behavior of "complete" if
     * the response is written successfully by invoking the listener
     * immediately using the given status.
     */
    public boolean cancelled() {
        return cancelled;
    }

    public ServerStatusAndMetadata withStatus(Status status) {
        final ServerStatusAndMetadata statusAndMetadata =
                new ServerStatusAndMetadata(status, metadata(), cancelled());
        statusAndMetadata.setResponseContent(setResponseContent());
        statusAndMetadata.completed(completed());
        return statusAndMetadata;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("status", status())
                          .add("metadata", metadata())
                          .add("cancelled", cancelled)
                          .add("completed", completed)
                          .add("setResponseContent", setResponseContent)
                          .toString();
    }
}
