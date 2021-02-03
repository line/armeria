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

import static java.util.Objects.requireNonNull;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.ws.rs.container.TimeoutHandler;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

import org.jboss.resteasy.core.AbstractAsynchronousResponse;
import org.jboss.resteasy.core.SynchronousDispatcher;

import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Implements {@link AbstractAsynchronousResponse}.
 */
final class ResteasyAsynchronousResponseImpl extends AbstractAsynchronousResponse {

    private final Object responseLock = new Object();
    private volatile boolean done;
    private volatile boolean cancelled;

    ResteasyAsynchronousResponseImpl(SynchronousDispatcher dispatcher, AbstractResteasyHttpRequest<?> request,
                                     ResteasyHttpResponseImpl response) {
        super(dispatcher, request, response);
        request.requestContext().whenRequestCancelled().thenAccept(this::whenRequestCancelled);
    }

    private AbstractResteasyHttpRequest<?> request() {
        return (AbstractResteasyHttpRequest<?>) request;
    }

    private ResteasyHttpResponseImpl response() {
        return (ResteasyHttpResponseImpl) response;
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
            onResponseCompletion(null);
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
            return internalResume(entity, this::onResponseCompletion);
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
            return internalResume(ex, this::onResponseCompletion);
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
                                  this::onResponseCompletion);
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
                                  this::onResponseCompletion);
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
                                  this::onResponseCompletion);
        }
    }

    @Override
    public boolean isSuspended() {
        return !done;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /**
     * Set/update the suspend timeout.
     * <p>
     * The new suspend timeout values override any timeout value previously specified.
     * The asynchronous response must be still in a {@link #isSuspended() suspended} state
     * for this method to succeed.
     * </p>
     *
     * @param time suspend timeout value in the give time {@code unit}. Value lower
     *             or equal to {@value #NO_TIMEOUT} causes the context to suspend indefinitely.
     * @param unit suspend timeout value time unit.
     * @return {@code true} if the suspend time out has been set, returns {@code false} in case
     *         the request processing is not in the {@link #isSuspended() suspended} state.
     */
    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        if (isDone()) {
            return false;
        }
        final ServiceRequestContext context = request().requestContext();
        if (time <= NO_TIMEOUT) {
            // prevent the request from ever being timed out
            context.clearRequestTimeout();
        } else {
            context.setRequestTimeoutMillis(unit.toMillis(time));
        }
        return true;
    }

    /**
     * Set/replace a time-out handler for the suspended asynchronous response.
     * <p>
     * The time-out handler will be invoked when the suspend period of this
     * asynchronous response times out. The job of the time-out handler is to
     * resolve the time-out situation by either resuming or cancelling the suspended response.
     * Extending the suspend period by setting a new suspend time-out< is not supported.
     * Note that in case the response is suspended {@link #NO_TIMEOUT indefinitely},
     * the time-out handler may never be invoked.
     * </p>
     *
     * @param handler response time-out handler.
     */
    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        requireNonNull(handler, "handler");
        super.setTimeoutHandler(handler);
    }

    /**
     * Invoked when request has been cancelled for any reason, including timeout.
     * Invokes configured {@link TimeoutHandler} when cancellation triggered by a timeout,
     * otherwise triggers {@link #cancel()}.
     * The {@link TimeoutHandler} must either {@link #resume(Object)}/{@link #resume(Throwable)} or
     * {@link #cancel()} the suspended response.
     * Resetting timeout inside the timeout handler is not supported.
     * @param cause {@link Throwable} cancellation cause
     */
    private void whenRequestCancelled(Throwable cause) {
        if (isDone()) {
            return;
        }
        final TimeoutHandler handler = timeoutHandler;
        if (cause instanceof TimeoutException && handler != null) {
            // if cancellation caused by a timeout - invoke the timeout handler
            // timeout handler must resume or cancel the response.
            handler.handleTimeout(this);
        } else {
            // cancel response by default
            cancel(); // responds with SERVICE_UNAVAILABLE
        }
    }

    private void onResponseCompletion(@Nullable Throwable throwable) {
        response().finish();
    }
}
