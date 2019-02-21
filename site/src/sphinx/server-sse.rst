.. _`Server-Sent Events`: https://www.w3.org/TR/eventsource/

.. _server-sse:

Serving Server-Sent Events
==========================

.. note::

    Visit `armeria-examples <https://github.com/line/armeria-examples>`_ to find a fully working example.

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

Adjusting the request timeout
-----------------------------

An event stream may be sent for a longer period than the configured timeout depending on the application.
It even can continue infinitely, for example streaming stock quotes. Such a long running stream may be
terminated prematurely because Armeria has the default request timeout of ``10`` seconds, i.e. your stream
will be broken after 10 seconds at most. Therefore, you must adjust the timeout if your event stream lasts
longer than the configured timeout. The following example shows how to adjust the timeout of a single request.
As you might know, it is not only for `Server-Sent Events`_, so you can use this method for any requests
which you want to adjust timeout for.

.. code-block:: java

    import java.time.Duration;
    import com.linecorp.armeria.common.sse.ServerSentEvent;
    import com.linecorp.armeria.server.Server;
    import com.linecorp.armeria.server.ServerBuilder;
    import com.linecorp.armeria.server.streaming.ServerSentEvents;
    import reactor.core.publisher.Flux;

    Server server = new ServerBuilder()
            // This service infinitely sends numbers as the data of events every second.
            .service("/long-sse", (ctx, req) -> {
                // Note that you MUST adjust the request timeout if you want to send events for a
                // longer period than the configured request timeout. The timeout can be disabled by
                // setting 0 like the below, but it is NOT RECOMMENDED in the real world application,
                // because it can leave a lot of unfinished requests.
                ctx.setRequestTimeout(Duration.ZERO);
                return ServerSentEvents.fromPublisher(
                        Flux.interval(Duration.ofSeconds(1))
                            .map(sequence -> ServerSentEvent.ofData(Long.toString(sequence))));
            })
            .build();
