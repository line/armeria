.. _`Server-Sent Events`: https://www.w3.org/TR/eventsource/

.. _server-sse:

Serving Server-Sent Events
==========================

A traditional web page has to send a request to the server in order to receive new data.
With `Server-Sent Events`_, however, it is possible for a server to push a new data to the web page
whenever it wants to.

Armeria provides several factory methods by :api:`ServerSentEvents` class which help a user to easily send
an event stream to the client. The following example shows how to build a server which serves services
sending a response with `Server-Sent Events`_.

.. code-block:: java

    import com.linecorp.armeria.common.sse.ServerSentEvent;
    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.streaming.ServerSentEvents;
    import reactor.core.publisher.Flux;

    Server server = new ServerBuilder()
            // Emit Server-Sent Events with the SeverSentEvent instances published by a publisher.
            .service("/sse1",
                     (ctx, req) -> ServerSentEvents.fromPublisher(
                             Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"))))
            // Emit Server-Sent Events with converting instances published by a publisher into
            // ServerSentEvent instances.
            .service("/sse2",
                     (ctx, req) -> ServerSentEvents.fromPublisher(
                             Flux.just("foo", "bar"), ServerSentEvent::ofData))
            .build();

Of course, Armeria provides :api:`@ProducesEventStream` annotation in order to convert the result objects
returned from an annotated service method into `Server-Sent Events`_. The following example shows how to
use the annotation.

.. code-block:: java

    import com.linecorp.armeria.common.sse.ServerSentEvent;
    import com.linecorp.armeria.server.annotation.Get;
    import com.linecorp.armeria.server.annotation.ProducesEventStream;
    import org.reactivestreams.Publisher;

    @Get("/sse")
    @ProducesEventStream
    public Publisher<ServerSentEvent> sse() {
        return Flux.just(ServerSentEvent.ofData("foo"), ServerSentEvent.ofData("bar"));
    }
