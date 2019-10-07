package com.linecorp.armeria.server;

import static com.linecorp.armeria.server.ServerConfig.validateMaxRequestLength;
import static com.linecorp.armeria.server.ServerConfig.validateRequestTimeoutMillis;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.logging.ContentPreviewer;
import com.linecorp.armeria.common.logging.ContentPreviewerFactory;
import com.linecorp.armeria.internal.ArmeriaHttpUtil;
import com.linecorp.armeria.server.logging.AccessLogWriter;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.function.Function;
import javax.annotation.Nullable;

public class AbstractServiceBindingBuilder0 implements ServiceBindingBuilder0 {
    @Nullable
    private Long requestTimeoutMillis;
    @Nullable
    private Long maxRequestLength;
    @Nullable
    private Boolean verboseResponses;
    @Nullable
    private ContentPreviewerFactory requestContentPreviewerFactory;
    @Nullable
    private ContentPreviewerFactory responseContentPreviewerFactory;
    @Nullable
    private Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> decorator;
    @Nullable
    private AccessLogWriter accessLogWriter;
    private boolean shutdownAccessLogWriterOnStop;


    /**
     * Sets the timeout of an HTTP request. If not set, {@link VirtualHost#requestTimeoutMillis()}
     * is used.
     *
     * @param requestTimeout the timeout. {@code 0} disables the timeout.
     */
    @Override
    public AbstractServiceBindingBuilder0 requestTimeout(Duration requestTimeout) {
        return requestTimeoutMillis(requireNonNull(requestTimeout, "requestTimeout").toMillis());
    }

    /**
     * Sets the timeout of an HTTP request in milliseconds. If not set,
     * {@link VirtualHost#requestTimeoutMillis()} is used.
     *
     * @param requestTimeoutMillis the timeout in milliseconds. {@code 0} disables the timeout.
     */
    public AbstractServiceBindingBuilder0 requestTimeoutMillis(long requestTimeoutMillis) {
        this.requestTimeoutMillis = validateRequestTimeoutMillis(requestTimeoutMillis);
        return this;
    }

    /**
     * Sets the maximum allowed length of an HTTP request. If not set,
     * {@link VirtualHost#maxRequestLength()} is used.
     *
     * @param maxRequestLength the maximum allowed length. {@code 0} disables the length limit.
     */
    public AbstractServiceBindingBuilder0 maxRequestLength(long maxRequestLength) {
        this.maxRequestLength = validateMaxRequestLength(maxRequestLength);
        return this;
    }

    /**
     * Sets whether the verbose response mode is enabled. When enabled, the service response will contain
     * the exception type and its full stack trace, which may be useful for debugging while potentially
     * insecure. When disabled, the service response will not expose such server-side details to the client.
     * If not set, {@link VirtualHost#verboseResponses()} is used.
     */
    public AbstractServiceBindingBuilder0 verboseResponses(boolean verboseResponses) {
        this.verboseResponses = verboseResponses;
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request of a {@link Service}.
     * If not set, {@link VirtualHost#requestContentPreviewerFactory()}
     * is used.
     */
    public AbstractServiceBindingBuilder0 requestContentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP response of a {@link Service}.
     * If not set, {@link VirtualHost#responseContentPreviewerFactory()}
     * is used.
     */
    public AbstractServiceBindingBuilder0 responseContentPreviewerFactory(ContentPreviewerFactory factory) {
        responseContentPreviewerFactory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of the {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets
     * any of the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview.
     */
    public AbstractServiceBindingBuilder0 contentPreview(int length) {
        return contentPreview(length, ArmeriaHttpUtil.HTTP_DEFAULT_CONTENT_CHARSET);
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for creating a {@link ContentPreviewer} which produces the
     * preview with the maximum {@code length} limit for an HTTP request/response of a {@link Service}.
     * The previewer is enabled only if the content type of an HTTP request/response meets any of
     * the following conditions:
     * <ul>
     *     <li>when it matches {@code text/*} or {@code application/x-www-form-urlencoded}</li>
     *     <li>when its charset has been specified</li>
     *     <li>when its subtype is {@code "xml"} or {@code "json"}</li>
     *     <li>when its subtype ends with {@code "+xml"} or {@code "+json"}</li>
     * </ul>
     * @param length the maximum length of the preview
     * @param defaultCharset the default charset used when a charset is not specified in the
     *                       {@code "content-type"} header
     */
    public AbstractServiceBindingBuilder0 contentPreview(int length, Charset defaultCharset) {
        return contentPreviewerFactory(ContentPreviewerFactory.ofText(length, defaultCharset));
    }

    /**
     * Sets the {@link ContentPreviewerFactory} for an HTTP request/response of a {@link Service}.
     */
    public AbstractServiceBindingBuilder0 contentPreviewerFactory(ContentPreviewerFactory factory) {
        requestContentPreviewerFactory(factory);
        responseContentPreviewerFactory(factory);
        return this;
    }

    /**
     * Sets the format of this {@link Service}'s access log. The specified {@code accessLogFormat} would be
     * parsed by {@link AccessLogWriter#custom(String)}.
     */
    public AbstractServiceBindingBuilder0 accessLogFormat(String accessLogFormat) {
        return accessLogWriter(AccessLogWriter.custom(requireNonNull(accessLogFormat, "accessLogFormat")),
            true);
    }

    /**
     * Sets the access log writer of this {@link Service}. If not set, {@link ServerConfig#accessLogWriter()}
     * is used.
     *
     * @param shutdownOnStop whether to shut down the {@link AccessLogWriter} when the {@link Server} stops
     */
    public AbstractServiceBindingBuilder0 accessLogWriter(AccessLogWriter accessLogWriter,
                                                         boolean shutdownOnStop) {
        this.accessLogWriter = requireNonNull(accessLogWriter, "accessLogWriter");
        shutdownAccessLogWriterOnStop = shutdownOnStop;
        return this;
    }

    /**
     * Decorates a {@link Service} with the specified {@code decorator}.
     *
     * @param decorator the {@link Function} that decorates the {@link Service}
     * @param <T> the type of the {@link Service} being decorated
     * @param <R> the type of the {@link Service} {@code decorator} will produce
     */
    public <T extends Service<HttpRequest, HttpResponse>, R extends Service<HttpRequest, HttpResponse>>
    AbstractServiceBindingBuilder0 decorator(Function<T, R> decorator) {
        requireNonNull(decorator, "decorator");

        @SuppressWarnings("unchecked")
        final Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>> castDecorator =
            (Function<Service<HttpRequest, HttpResponse>, Service<HttpRequest, HttpResponse>>) decorator;

        if (this.decorator != null) {
            this.decorator = this.decorator.andThen(castDecorator);
        } else {
            this.decorator = castDecorator;
        }

        return this;
    }

    public Service<HttpRequest, HttpResponse> decorate (Service<HttpRequest, HttpResponse> service){
        if (decorator == null) {
            return service;
        }

        return service.decorate(decorator);
    }


}
