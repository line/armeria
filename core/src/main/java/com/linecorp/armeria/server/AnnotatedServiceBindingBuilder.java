package com.linecorp.armeria.server;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceElement;
import com.linecorp.armeria.internal.annotation.AnnotatedHttpServiceFactory;
import java.util.List;
import java.util.function.Function;
import javax.annotation.Nullable;

public class AnnotatedServiceBindingBuilder extends AbstractServiceBindingBuilder0{

    private final ServerBuilder serverBuilder;
    private String pathPrefix = "/";
    @Nullable
    private Object service;
    @Nullable
    private Iterable<?> exceptionHandlersAndConverters;

    public AnnotatedServiceBindingBuilder(ServerBuilder serverBuilder) {
        this.serverBuilder = requireNonNull(serverBuilder, "serverBuilder");
    }


    public ServerBuilder build() {

        final List<AnnotatedHttpServiceElement> elements =
            AnnotatedHttpServiceFactory.find(pathPrefix, service, exceptionHandlersAndConverters);
        elements.forEach(e -> {
            Service<HttpRequest, HttpResponse> s = e.service();
            // Apply decorators which are specified in the service class.
            s = e.decorator().apply(s);
            // Apply decorators which are passed via annotatedService() methods.
            s = decorate(s);

            // If there is a decorator, we should add one more decorator which handles an exception
            // raised from decorators.
            if (s != e.service()) {
                s = e.service().exceptionHandlingDecorator().apply(s);
            }
            addServiceConfigBuilder(e.route(), s);
        });
        return serverBuilder;
    }

    private void addServiceConfigBuilder(Route route, Service<HttpRequest, HttpResponse> service) {
        serverBuilder.serviceConfigBuilder(new ServiceConfigBuilder(route, service));
    }


    public AnnotatedServiceBindingBuilder pathPrefix(String pathPrefix) {
        //todo: nulll/empty check
        this.pathPrefix = requireNonNull(pathPrefix, "pathPrefix");
        return this;
    }

    public AnnotatedServiceBindingBuilder service(Object service) {
        this.service  = requireNonNull(service, "service");
        return this;
    }

    public AnnotatedServiceBindingBuilder exceptionHandlersAndConverters(Iterable<?> exceptionHandlersAndConverters) {
        //todo instead of taking iterable, take individual object and add it to list
        this.exceptionHandlersAndConverters = requireNonNull(exceptionHandlersAndConverters,
                                        "exceptionHandlersAndConverters");
        return this;
    }

    @Override
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>> AnnotatedServiceBindingBuilder decorator(Function<T, R> decorator) {
        return (AnnotatedServiceBindingBuilder) super.decorator(decorator);
    }
}
