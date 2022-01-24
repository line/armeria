package example.armeria.contextpropagation.dagger;

import java.util.concurrent.Executor;

import javax.inject.Provider;
import javax.inject.Singleton;

import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.spotify.futures.ListenableFuturesExtra;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServiceRequestContext;

import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.producers.Production;

public class Main {

    @Module(subcomponents = MainGraph.Component.class)
    abstract static class MainModule {
        @Provides
        @Singleton
        static WebClient backendClient() {
            return WebClient.of("http://localhost:8081");
        }

        @Provides
        @Singleton
        static Server server(Provider<MainGraph.Component.Builder> graphBuilder) {
            return Server.builder()
                         .http(8080)
                         .serviceUnder("/", ((ctx, req) ->
                                 HttpResponse.from(
                                         ListenableFuturesExtra.toCompletableFuture(
                                                 graphBuilder.get().request(req).build().execute()))))
                         .build();
        }

        // These provisions are not @Singleton. Every time a producer component is initialized, they will be
        // evaluated once to satisfy the component's requirements.
        @Provides
        static ServiceRequestContext context() {
            return ServiceRequestContext.current();
        }

        // This indicates that all methods in a producer component should be run in this Executor. This ensures
        // the correct RequestContext is always mounted and used correctly, for example to make sure backend
        // calls are traced.
        @Provides
        @Production
        static Executor executor(ServiceRequestContext ctx) {
            return ctx.eventLoop();
        }

        // ServiceRequestContext.blockingTaskExecutor should be used to run blocking logic with context mounted
        // appropriately. Because Dagger only works with ListenableFuture, we provide this convenience wrapper.
        @Provides
        static ListeningScheduledExecutorService blockingExecutor(ServiceRequestContext ctx) {
            return MoreExecutors.listeningDecorator(ctx.blockingTaskExecutor());
        }
    }

    @Component(modules = MainModule.class)
    @Singleton
    interface MainComponent {
        Server server();
    }

    public static void main(String[] args) {
        final Server backend = Server.builder()
                                     .service("/square/{num}", ((ctx, req) -> {
                                         final long num = Long.parseLong(ctx.pathParam("num"));
                                         return HttpResponse.of(Long.toString(num * num));
                                     }))
                                     .http(8081)
                                     .build();

        final Server frontend = DaggerMain_MainComponent.create().server();

        backend.closeOnShutdown();
        frontend.closeOnShutdown();

        backend.start().join();
        frontend.start().join();
    }
}
