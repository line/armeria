package com.linecorp.armeria.server.graphql;

import com.linecorp.armeria.common.websocket.WebSocketFrame;
import com.linecorp.armeria.common.websocket.WebSocketWriter;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class GraphqlWebSocketSubscriber implements Subscriber<WebSocketFrame> {
    private final GraphqlWSSubProtocol graphqlWSSubProtocol;
    private final WebSocketWriter outgoing;
    Subscription subscription;

    GraphqlWebSocketSubscriber(GraphqlWSSubProtocol graphqlWSSubProtocol, WebSocketWriter outgoing) {
        this.graphqlWSSubProtocol = graphqlWSSubProtocol;
        this.outgoing = outgoing;
    }

    @Override
    public void onSubscribe(Subscription s) {
        subscription = s;
        s.request(1);
    }

    @Override
    public void onNext(WebSocketFrame webSocketFrame) {
        switch (webSocketFrame.type()) {
            case TEXT:
                // Parse the graphql-ws sub protocol
                graphqlWSSubProtocol.handle(webSocketFrame.text(), outgoing);
                subscription.request(1);
                break;
            case PING:
                outgoing.writePong();
                subscription.request(1);
                break;
            case CLOSE:
                subscription.cancel();
                outgoing.close();
                break;
            case PONG:
                // These below will never happen?
            case BINARY:
            case CONTINUATION:
                subscription.request(1);
                break;
            default:
                throw new IllegalStateException("Unexpected value: " + webSocketFrame.type());
        }
    }

    @Override
    public void onError(Throwable t) {

    }

    @Override
    public void onComplete() {

    }
}
