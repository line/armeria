package com.linecorp.armeria.server.graphql;

import com.fasterxml.jackson.core.JsonProcessingException;
import graphql.ExecutionResult;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

import java.util.concurrent.atomic.AtomicReference;

class GraphqlSubscriber implements Subscriber<ExecutionResult> {
    private final GraphqlSubProtocol protocol;
    private final String operationId;
    private boolean completed = false;
    private final AtomicReference<Subscription> subscriptionRef = new AtomicReference<>();

    public GraphqlSubscriber(String operationId, GraphqlSubProtocol protocol) {
        this.operationId = operationId;
        this.protocol = protocol;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscriptionRef.set(s);
        request(1);
    }

    @Override
    public void onNext(ExecutionResult executionResult) {
        if (completed) {
            return;
        }
        try {
            if (executionResult.getErrors().isEmpty()) {
                protocol.sendResult(operationId, executionResult);
                request(1);
            } else {
                protocol.sendGraphqlErrors(executionResult.getErrors());
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onError(Throwable t) {
        try {
            protocol.sendError(t);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onComplete() {

    }

    public void setCompleted() {
        completed = true;
    }

    private void request(int n) {
        Subscription subscription = subscriptionRef.get();
        if (subscription != null) {
            subscription.request(n);
        }
    }
}
