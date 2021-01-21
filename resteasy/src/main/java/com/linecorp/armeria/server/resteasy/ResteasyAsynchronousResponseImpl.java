/*
 * Copyright 2021 LINE Corporation
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

package com.linecorp.armeria.server.resteasy;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.core.AbstractAsynchronousResponse;
import org.jboss.resteasy.core.SynchronousDispatcher;

/**
 * Implements {@link AbstractAsynchronousResponse}.
 */
final class ResteasyAsynchronousResponseImpl extends AbstractAsynchronousResponse {

    private final Object responseLock = new Object();
    private volatile boolean done;
    private volatile boolean cancelled;

    ResteasyAsynchronousResponseImpl(SynchronousDispatcher dispatcher,
                                     ResteasyHttpRequestImpl request, ResteasyHttpResponseImpl response) {
        super(dispatcher, request, response);
    }

    @Override
    public void initialRequestThreadFinished() {
        // done
    }

    @Override
    public void complete() {
        synchronized (responseLock) {
            if (done) {
                return;
            }
            if (cancelled) {
                return;
            }
            done = true;
            finishResponse();
        }
    }

    @Override
    public boolean resume(Object entity) {
        synchronized (responseLock) {
            if (done) {
                return false;
            }
            if (cancelled) {
                return false;
            }
            done = true;
            return internalResume(entity, t -> finishResponse());
        }
    }

    @Override
    public boolean resume(Throwable ex) {
        synchronized (responseLock) {
            if (done) {
                return false;
            }
            if (cancelled) {
                return false;
            }
            done = true;
            return internalResume(ex, t -> finishResponse());
        }
    }

    @Override
    public boolean cancel() {
        synchronized (responseLock) {
            if (cancelled) {
                return true;
            }
            if (done) {
                return false;
            }
            done = true;
            cancelled = true;
            return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE).build(),
                                  t -> finishResponse());
        }
    }

    @Override
    public boolean cancel(int retryAfter) {
        synchronized (responseLock) {
            if (cancelled) {
                return true;
            }
            if (done) {
                return false;
            }
            done = true;
            cancelled = true;
            return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                                          .header(HttpHeaders.RETRY_AFTER, retryAfter).build(),
                                  t -> finishResponse());
        }
    }

    @Override
    public boolean cancel(Date retryAfter) {
        synchronized (responseLock) {
            if (cancelled) {
                return true;
            }
            if (done) {
                return false;
            }
            done = true;
            cancelled = true;
            return internalResume(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                                          .header(HttpHeaders.RETRY_AFTER, retryAfter).build(),
                                  t -> finishResponse());
        }
    }

    synchronized void finishResponse() {
        ((ResteasyHttpResponseImpl) response).finish();
    }

    @Override
    public boolean isSuspended() {
        return !done && !cancelled;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        ((ResteasyHttpRequestImpl) request).requestContext().whenRequestTimedOut().thenRun(() -> {
            if (timeoutHandler != null) {
                timeoutHandler.handleTimeout(this);
            }
            if (done) {
                return;
            }
            resume(new ServiceUnavailableException());
        });
        ((ResteasyHttpRequestImpl) request).requestContext().setRequestTimeout(
                Duration.ofMillis(unit.toMillis(time)));
        return true;
    }
}
