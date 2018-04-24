.. _@ConsumeType: apidocs/index.html?com/linecorp/armeria/server/annotation/ConsumeType.html
.. _@Decorator: apidocs/index.html?com/linecorp/armeria/server/annotation/Decorator.html
.. _@Decorators: apidocs/index.html?com/linecorp/armeria/server/annotation/Decorators.html
.. _@DecoratorFactory: apidocs/index.html?com/linecorp/armeria/server/annotation/DecoratorFactory.html
.. _@Default: apidocs/index.html?com/linecorp/armeria/server/annotation/Default.html
.. _@Delete: apidocs/index.html?com/linecorp/armeria/server/annotation/Delete.html
.. _@ExceptionHandler: apidocs/index.html?com/linecorp/armeria/server/annotation/ExceptionHandler.html
.. _@Get: apidocs/index.html?com/linecorp/armeria/server/annotation/Get.html
.. _@Head: apidocs/index.html?com/linecorp/armeria/server/annotation/Head.html
.. _@Header: apidocs/index.html?com/linecorp/armeria/server/annotation/Header.html
.. _@LoggingDecorator: apidocs/index.html?com/linecorp/armeria/server/annotation/decorator/LoggingDecorator.html
.. _@Options: apidocs/index.html?com/linecorp/armeria/server/annotation/Options.html
.. _@Order: apidocs/index.html?com/linecorp/armeria/server/annotation/Order.html
.. _@Param: apidocs/index.html?com/linecorp/armeria/server/annotation/Param.html
.. _@Patch: apidocs/index.html?com/linecorp/armeria/server/annotation/Patch.html
.. _@Path: apidocs/index.html?com/linecorp/armeria/server/annotation/Path.html
.. _@Post: apidocs/index.html?com/linecorp/armeria/server/annotation/Post.html
.. _@ProduceType: apidocs/index.html?com/linecorp/armeria/server/annotation/ProduceType.html
.. _@Put: apidocs/index.html?com/linecorp/armeria/server/annotation/Put.html
.. _@RequestConverter: apidocs/index.html?com/linecorp/armeria/server/annotation/RequestConverter.html
.. _@RequestObject: apidocs/index.html?com/linecorp/armeria/server/annotation/RequestObject.html
.. _@ResponseConverter: apidocs/index.html?com/linecorp/armeria/server/annotation/ResponseConverter.html
.. _@Trace: apidocs/index.html?com/linecorp/armeria/server/annotation/Trace.html
.. _AggregatedHttpMessage: apidocs/index.html?com/linecorp/armeria/common/AggregatedHttpMessage.html
.. _BeanRequestConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/BeanRequestConverterFunction.html
.. _ByteArrayRequestConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/ByteArrayRequestConverterFunction.html
.. _DecoratingServiceFunction: apidocs/index.html?com/linecorp/armeria/server/DecoratingServiceFunction.html
.. _DecoratorFactoryFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/DecoratorFactoryFunction.html
.. _ExceptionHandlerFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/ExceptionHandlerFunction.html
.. _HttpParameters: apidocs/index.html?com/linecorp/armeria/common/HttpParameters.html
.. _HttpRequest: apidocs/index.html?com/linecorp/armeria/common/HttpRequest.html
.. _HttpResponse: apidocs/index.html?com/linecorp/armeria/common/HttpResponse.html
.. _HttpResponseException: apidocs/index.html?com/linecorp/armeria/server/HttpResponseException.html
.. _HttpStatusException: apidocs/index.html?com/linecorp/armeria/server/HttpStatusException.html
.. _JacksonRequestConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/JacksonRequestConverterFunction.html
.. _LoggingService: apidocs/index.html?com/linecorp/armeria/server/logging/LoggingService.html
.. _PathMapping: apidocs/index.html?com/linecorp/armeria/server/PathMapping.html
.. _Request: apidocs/index.html?com/linecorp/armeria/common/Request.html
.. _RequestContext: apidocs/index.html?com/linecorp/armeria/common/RequestContext.html
.. _RequestConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/RequestConverterFunction.html
.. _ResponseConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/ResponseConverterFunction.html
.. _ServerBuilder: apidocs/index.html?com/linecorp/armeria/server/ServerBuilder.html
.. _Service: apidocs/index.html?com/linecorp/armeria/server/Service.html
.. _ServiceRequestContext: apidocs/index.html?com/linecorp/armeria/server/ServiceRequestContext.html
.. _StringRequestConverterFunction: apidocs/index.html?com/linecorp/armeria/server/annotation/StringRequestConverterFunction.html

.. _server-annotated-service:

Annotated HTTP Service
======================

Armeria provides a way to write an HTTP service using annotations. It helps a user make his or her code
simple and easy to understand. A user is able to run an HTTP service by fewer lines of code using
annotations as follows. ``hello()`` method in the example would be mapped to the path of ``/hello/{name}``
with an HTTP GET method.

.. code-block:: java

    ServerBuilder sb = new ServerBuilder();
    sb.annotatedService(new Object() {
        @Get("/hello/{name}")
        public HttpResponse hello(@Param("name") String name) {
            return HttpResponse.of(HttpStatus.OK,
                                   MediaType.PLAIN_TEXT_UTF_8,
                                   "Hello, %s!", name);
        }
    });

Mapping HTTP service methods
----------------------------

To map a service method in an annotated HTTP service class to an HTTP path, it has to be annotated with one of
HTTP method annotations. The following is the list of HTTP method annotations where each of them is mapped
to an HTTP method.

- `@Get`_
- `@Head`_
- `@Post`_
- `@Put`_
- `@Delete`_
- `@Options`_
- `@Patch`_
- `@Trace`_

To handle an HTTP request with a service method, you can annotate your service method simply as follows.

.. code-block:: java

    public class MyAnnotatedService {
        @Get("/hello")
        public HttpResponse hello() { ... }
    }

There are 5 PathMapping_ types provided for describing a path.

- Exact mapping, e.g. ``/hello`` or ``exact:/hello``

  - a service method will handle the path exactly matched with the specified path.

- Mapping with path prefix, e.g. ``prefix:/hello``

  - a service method will handle every path which starts with the specified prefix.

- Mapping with path variables, e.g ``/hello/{name}`` or ``/hello/:name``

  - a service method will handle the path matched with the specified path pattern. A path variable in the
    specified pattern may be mapped to a parameter of the service method.

- Mapping with regular expression, e.g. ``regex:^/hello/(?<name>.*)$``

  - a service method will handle the path matched with the specified regular expression. If a named capturing
    group exists in the regular expression, it may be mapped to a parameter of the service method.

- Mapping with glob pattern, e.g. ``glob:/*/hello/**``

  - a service method will handle the path matched with the specified glob pattern. Each wildcard is mapped to
    an index which starts with ``0``, so it may be mapped to a parameter of the service method.

You can get the value of a path variable, a named capturing group of the regular expression or wildcards of
the glob pattern in your service method by annotating a parameter with `@Param`_ as follows.
Please refer to :ref:`parameter-injection` for more information about `@Param`_.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello/{name}")
        public HttpResponse pathvar(@Param("name") String name) { ... }

        @Get("regex:^/hello/(?<name>.*)$")
        public HttpResponse regex(@Param("name") String name) { ... }

        @Get("glob:/*/hello/**")
        public HttpResponse glob(@Param("0") String prefix, @Param("1") String name) { ... }
    }

Every service method in the examples so far had a single HTTP method annotation with it. What if you want
to map more than one HTTP method to your service method? You can use `@Path`_ annotation to specify a path
and use the HTTP method annotations without a path to map multiple HTTP methods, e.g.

.. code-block:: java

    public class MyAnnotatedService {
        @Get
        @Post
        @Put
        @Delete
        @Path("/hello")
        public HttpResponse hello() { ... }
    }

.. _parameter-injection:

Parameter injection
-------------------

Let's see the example in the above section again.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello/{name}")
        public HttpResponse pathvar(@Param("name") String name) { ... }

        @Get("regex:^/hello/(?<name>.*)$")
        public HttpResponse regex(@Param("name") String name) { ... }

        @Get("glob:/*/hello/**")
        public HttpResponse glob(@Param("0") String prefix, @Param("1") String name) { ... }
    }

A value of a parameter ``name`` is automatically injected as a ``String`` by Armeria.
Armeria will try to convert the value appropriately if the parameter type is not ``String``.
``IllegalArgumentException`` will be raised if the conversion fails or the parameter type is not
one of the following supported types:

- ``boolean`` or ``Boolean``
- ``byte`` or ``Byte``
- ``short`` or ``Short``
- ``integer`` or ``Integer``
- ``long`` or ``Long``
- ``float`` or ``Float``
- ``double`` or ``Double``
- ``String``
- ``Enum``

Note that you can omit the value of `@Param`_ if you compiled your code with ``-parameters`` javac option.
In this case the variable name is used as the value.

.. code-block:: java

    public class MyAnnotatedService {
        @Get("/hello/{name}")
        public HttpResponse hello1(@Param String name) { ... }
    }

.. note::

    You can configure your build tool to add ``-parameters`` javac option as follows.

    .. code-block:: groovy

        // Gradle:
        tasks.withType(JavaCompile) {
            options.compilerArgs += '-parameters'
        }

    .. code-block:: xml

        <!-- Maven -->
        <project>
          <build>
            <pluigins>
              <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                  <compilerArgs>
                    <arg>-parameters</arg>
                  </compilerArgs>
                </configuration>
              </plugin>
            </plugins>
          </build>
        </project>

Injecting a parameter as an ``Enum`` type
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

``Enum`` type is also automatically converted if you annotate a parameter of your service method with
`@Param`_ annotation. If your ``Enum`` type can be handled in a case-insensitive way, Armeria
automatically converts the string value of a parameter to a value of ``Enum`` in a case-insensitive way.
Otherwise, case-sensitive exact match will be performed.

.. code-block:: java

    public enum CaseInsensitive {
        ALPHA, BRAVO, CHARLIE
    }

.. code-block:: java

    public enum CaseSensitive {
        ALPHA, alpha
    }

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello1/{there}")
        public HttpResponse hello1(@Param("there") CaseInsensitive there) {
            // 'there' is converted in a case-insensitive way.
        }

        @Get("/hello2/{there}")
        public HttpResponse hello2(@Param("there") CaseSensitive there) {
            // 'there' must be converted in a case-sensitive way.
            // So 'ALPHA' and 'alpha' are only acceptable.
        }
    }

Getting an HTTP parameter
^^^^^^^^^^^^^^^^^^^^^^^^^

When the value of `@Param`_ annotation is not shown in the path pattern, it will be handled as a parameter
name of the query string of the request. If you have a service class like the example below and a user sends an
HTTP GET request with URI of ``/hello1?name=armeria``, the service method will get ``armeria`` as the value
of parameter ``name``. If there is no parameter named ``name`` in the query string, the parameter ``name``
of the method would be ``null``. If you want to avoid ``null`` in this case, you can use `@Default`_
annotation or ``Optional<?>`` class, e.g. ``hello2`` and ``hello3`` methods below, respectively.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello1")
        public HttpResponse hello1(@Param("name") String name) { ... }

        @Get("/hello2")
        public HttpResponse hello2(@Param("name") @Default("armeria") String name) { ... }

        @Get("/hello3")
        public HttpResponse hello3(@Param("name") Optional<String> name) {
            String clientName = name.orElse("armeria");
            // ...
        }
    }

If an HTTP POST request with a ``Content-Type: x-www-form-urlencoded`` is received and no `@Param`_ value
appears in the path pattern, Armeria will aggregate the received request and decode its body as a URL-encoded
form. After that, Armeria will inject the decoded value into the parameter.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello4")
        public HttpResponse hello4(@Param("name") String name) {
            // 'x-www-form-urlencoded' request will be aggregated. The other requests may get
            // a '400 Bad Request' because there is no way to inject a mandatory parameter 'name'.
        }
    }

.. _header-injection:

Getting an HTTP header
^^^^^^^^^^^^^^^^^^^^^^

Armeria also provides `@Header`_ annotation to inject an HTTP header value into a parameter. The parameter
annotated with `@Header`_ can also be specified as one of the built-in types as follows. `@Default`_ and
``Optional<?>`` are also supported.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello1")
        public HttpResponse hello1(@Header("Authorization") String auth) { ... }

        @Post("/hello2")
        public HttpResponse hello2(@Header("Content-Length") long contentLength) { ... }
    }

Note that you can omit the value of `@Header`_  if you compiled your code with ``-parameters`` javac option.
Read :ref:`parameter-injection` for more information.
In this case the variable name is used as the value, but it will be converted to hyphen-separated lowercase
string to be suitable for general HTTP header names. e.g. a variable name ``contentLength`` or
``content_length`` will be converted to ``content-length`` as the value of `@Header`_.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello2")
        public HttpResponse hello2(@Header long contentLength) { ... }
    }

Other classes automatically injected
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The following classes are automatically injected when you specify them on the parameter list of your method.

- RequestContext_
- ServiceRequestContext_
- Request_
- HttpRequest_
- AggregatedHttpMessage_
- HttpParameters_

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello1")
        public HttpResponse hello1(ServiceRequestContext ctx, HttpRequest req) {
            // Use the context and request inside a method.
        }

        @Post("/hello2")
        public HttpResponse hello2(AggregatedHttpMessage aggregatedMessage) {
            // Armeria aggregates the received HttpRequest and calls this method with the aggregated request.
        }

        @Get("/hello3")
        public HttpResponse hello3(HttpParameters httpParameters) {
            // 'httpParameters' holds the parameters parsed from a query string of a request.
        }

        @Post("/hello4")
        public HttpResponse hello4(HttpParameters httpParameters) {
            // If a request has a url-encoded form as its body, it can be accessed via 'httpParameters'.
        }
    }

Handling exceptions
-------------------

It is often useful to extract exception handling logic from service methods into a separate common class.
Armeria provides `@ExceptionHandler`_ annotation to transform an exception into a response. You can write
your own exception handler by implementing ExceptionHandlerFunction_ interface and annotate your service
object or method with `@ExceptionHandler`_ annotation. Here is an example of an exception handler.
If your exception handler is not able to handle a given exception, you can call
``ExceptionHandlerFunction.fallthrough()`` to pass the exception to the next exception handler.

.. code-block:: java

    public class MyExceptionHandler implements ExceptionHandlerFunction {
        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req, Throwable cause) {
            if (cause instanceof MyServiceException) {
                return HttpResponse.of(HttpStatus.CONFLICT);
            }

            // To the next exception handler.
            return ExceptionHandlerFunction.fallthrough();
        }
    }

You can annotate at class level to catch an exception from every method in your service class.

.. code-block:: java

    @ExceptionHandler(MyExceptionHandler.class)
    public class MyAnnotatedService {
        @Get("/hello")
        public HttpResponse hello() { ... }
    }

You can also annotate at method level to catch an exception from a single method in your service class.

.. code-block:: java

    public class MyAnnotatedService {
        @Get("/hello")
        @ExceptionHandler(MyExceptionHandler.class)
        public HttpResponse hello() { ... }
    }

If there is no exception handler which is able to handle an exception, the exception would be passed to
the default exception handler. It handles ``IllegalArgumentException``, HttpStatusException_ and
HttpResponseException_ by default. ``IllegalArgumentException`` would be converted into ``400 Bad Request``
response, and the other two exceptions would be converted into a response with the status code which
they are holding. For another exceptions, ``500 Internal Server Error`` would be sent to the client.

Conversion between an HTTP message and a Java object
----------------------------------------------------

Converting an HTTP request to a Java object
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In some cases like receiving a JSON document from a client, it may be useful to convert the document to
a Java object automatically. Armeria provides `@RequestConverter`_ and `@RequestObject`_ annotations
so that such conversion can be done conveniently.
You can write your own request converter by implementing RequestConverterFunction_ as follows.
Similar to the exception handler, you can call ``RequestConverterFunction.fallthrough()`` when your request
converter is not able to convert the request.

.. code-block:: java

    public class MyRequestConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) {
            if (expectedResultType == MyObject.class) {
                // Convert the request to a Java object.
                return new MyObject(request.content());
            }

            // To the next request converter.
            return RequestConverterFunction.fallthrough();
        }
    }

Then, you can write your service method as follows. Note that a request converter will work on the parameters
which are annotated with `@RequestObject`_.

.. code-block:: java

    @RequestConverter(MyRequestConverter.class)
    public class MyAnnotatedService {

        @Post("/hello")
        public HttpResponse hello(@RequestObject MyObject myObject) {
            // MyRequestConverter will be used to convert a request.
            // ...
        }

        @Post("/hola")
        @RequestConverter(MySpanishRequestConverter.class)
        public HttpResponse hola(@RequestObject MySpanishObject myObject) {
            // MySpanishRequestConverter will be tried to convert a request first.
            // MyRequestConverter will be used if MySpanishRequestConverter fell through.
            // ...
        }
    }

Armeria also provides built-in request converters such as, BeanRequestConverterFunction_ for Java Beans,
JacksonRequestConverterFunction_ for JSON documents, StringRequestConverterFunction_ for text contents
and ByteArrayRequestConverterFunction_ for binary contents. They will be applied after your request converters
by default, so you can use these built-in converters by just putting `@RequestObject`_ annotation on the
parameters which you want to convert.

In some cases, `@RequestObject`_ annotation may have a request converter as its value.
Assume that you have a Java class named ``MyRequest`` that it is usually able to be converted by
``MyDefaultRequestConverter``. But what if there is only one method which has a parameter of ``MyRequest``
that you have to convert it differently? In this case, you may specify a request converter with
`@RequestObject`_ annotation. In the example, ``MySpecialRequestConverter`` will be used first for
converting ``MyRequest``.

.. code-block:: java

    @RequestConverter(MyDefaultRequestConverter.class)
    public class MyAnnotatedService {
        @Post("/hello")
        public HttpResponse hello(
            @RequestObject(MySpecialRequestConverter.class) MyRequest myRequest) { ... }
    }


Injecting value of parameters and HTTP headers into a Java object
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

BeanRequestConverterFunction_ is a built-in request converter for Java object. You can use it by putting
`@RequestObject`_ annotation on the parameters which you want to convert.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello")
        public HttpResponse hello(@RequestObject MyRequestObject myRequestObject) { ... }
    }

Besides the annotated service class, you also need to create ``MyRequestObject`` and put `@Param`_ or
`@Header`_ annotations on any of the following elements, to inject the path parameters, HTTP parameters
or HTTP headers:

- Fields
- Constructors with only one parameter
- Methods with only one parameter
- Constructor parameters
- Method parameters


.. code-block:: java

    public class MyRequestObject {
        @Param("name") // This field will be injected by the value of parameter "name".
        private String name;

        @Header("age") // This field will be injected by the value of HTTP header "age".
        private int age;

        // You can omit the value of @Param or @Header if you compiled your code with ``-parameters`` javac option.
        @Param         // This field will be injected by the value of parameter "gender".
        private String gender;

        @Header        // This field will be injected by the value of HTTP header "accept-language".
        private String acceptLanguage;

        @Param("address") // You can annotate a single parameter method with @Param or @Header.
        public void setAddress(String address) { ... }

        @Header("id") // You can annotate a single parameter constructor with @Param or @Header.
        public MyRequestObject(long id) { ... }

        // You can annotate all parameters of method or constructor with @Param or @Header.
        public void init(@Header("permissions") String permissions, @Param("client-id") int clientId)
    }

The usage of `@Param`_ or `@Header`_ annotations on Java object elements is much like using them on the
parameters of service method.
Please refer to :ref:`parameter-injection`, and :ref:`header-injection` for more information.


Converting a Java object to an HTTP response
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Every object returned by an annotated service method can be converted to an HTTP response message by
response converters, except for HttpResponse_ and AggregatedHttpMessage_ which are already in a
form of response message. You can also write your own response converter by implementing
ResponseConverterFunction_ as follows. Also similar to RequestConverterFunction_, you can call
``ResponseConverterFunction.fallthrough()`` when your response converter is not able to convert the result
to an HttpResponse_.

.. code-block:: java

    public class MyResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, Object result) {
            if (result instanceof MyObject) {
                return HttpResponse.of(HttpStatus.OK,
                                       MediaType.PLAIN_TEXT_UTF_8,
                                       "Hello, %s!", ((MyObject) result).processedName());
            }

            // To the next response converter.
            return ResponseConverterFunction.fallthrough();
        }
    }

You can annotate your service method and class as follows.

.. code-block:: java

    @ResponseConverter(MyResponseConverter.class)
    public class MyAnnotatedService {

        @Post("/hello")
        public MyObject hello() {
            // MyResponseConverter will be used to make a response.
            // ...
        }

        @Post("/hola")
        @ResponseConverter(MySpanishResponseConverter.class)
        public MyObject hola() {
            // MySpanishResponseConverter will be tried to convert MyObject to a response first.
            // MyResponseConverter will be used if MySpanishResponseConverter fell through.
            // ...
        }
    }

Armeria supports :ref:`media_type_nego`. So you may want to get a negotiated media type in order to set
a ``Content-Type`` header on your response. In this case, you can access it in your response converter
as follows.

.. code-block:: java

    public class MyResponseConverter implements ResponseConverterFunction {
        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, Object result)  {
            MediaType mediaType = ctx.negotiatedProduceType();
            if (mediaType != null) {
                // Do something based on the media type.
                // ...
            }
        }
    }

.. _configure-using-serverbuilder:

Using ServerBuilder_ to configure converters and exception handlers
-------------------------------------------------------------------

You can specify converters and exception handlers using ServerBuilder_, without using the annotations
explained in the previous sections::

    sb.annotatedService(new MyAnnotatedService(),
                        new MyExceptionHandler(), new MyRequestConverter(), new MyResponseConverter());

Also, they have a different method signature for conversion and exception handling so you can even write them
in a single class and add it to your ServerBuilder_ at once, e.g.

.. code-block:: java

    public class MyAllInOneHandler implements RequestConverterFunction,
                                              ResponseConverterFunction,
                                              ExceptionHandlerFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) { ... }

        @Override
        public HttpResponse convertResponse(ServiceRequestContext ctx, Object result) { ... }

        @Override
        public HttpResponse handleException(RequestContext ctx, HttpRequest req,
                                            Throwable cause) { ... }
    }

    // ...

    sb.annotatedService(new MyAnnotatedService(), new MyAllInOneHandler());

When you specify exception handlers in a mixed manner like below, they will be evaluated in the following
order commented. It is also the same as the evaluation order of the converters.

.. code-block:: java

    @ExceptionHandler(MyClassExceptionHandler3.class)           // order 3
    @ExceptionHandler(MyClassExceptionHandler4.class)           // order 4
    public class MyAnnotatedService {
        @Get("/hello")
        @ExceptionHandler(MyMethodExceptionHandler1.class)      // order 1
        @ExceptionHandler(MyMethodExceptionHandler2.class)      // order 2
        public HttpResponse hello() { ... }
    }

    // ...

    sb.annotatedService(new MyAnnotatedService(),
                        new MyGlobalExceptionHandler5(),        // order 5
                        new MyGlobalExceptionHandler6());       // order 6

Decorating an annotated service
-------------------------------

Every Service_ can be wrapped by another Service_ in Armeria (Refer to :ref:`server-decorator` for more
information). Simply, you can write your own decorator by implementing DecoratingServiceFunction_ interface
as follows.

.. code-block:: java

    public class MyDecorator implements DecoratingServiceFunction<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx, HttpRequest req) {
            // ... Do something ...
            return delegate.serve(ctx, req);
        }
    }

Then, annotate your class or method with a `@Decorator`_ annotation. In the following example, ``MyDecorator``
will handle a request first, then ``AnotherDecorator`` will handle the request next, and finally ``hello()``
method will handle the request.

.. code-block:: java

    @Decorator(MyDecorator.class)
    public class MyAnnotatedService {
        @Decorator(AnotherDecorator.class)
        @Get("/hello")
        public HttpResponse hello() { ... }
    }

Decorating an annotated service with a custom decorator annotation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As you read earlier, you can write your own decorator with DecoratingServiceFunction_ interface. If your
decorator does not require any parameter, that is fine. However, what if your decorator requires a parameter?
In this case, you can create your own decorator annotation. Let's see the following custom decorator
annotation which applies LoggingService_ to an annotated service.

.. note::

    This example is actually just a copy of what Armeria provides out of the box. In reality,
    you could just use `@LoggingDecorator`_, without writing your own one.

.. code-block:: java

    @DecoratorFactory(LoggingDecoratorFactoryFunction.class)
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    public @interface LoggingDecorator {

        // Specify parameters for your decorator like below.
        LogLevel requestLogLevel() default LogLevel.TRACE;

        LogLevel successfulResponseLogLevel() default LogLevel.TRACE;

        LogLevel failureResponseLogLevel() default LogLevel.WARN;

        float samplingRate() default 1.0f;

        // A special parameter in order to specify the order of a decorator.
        int order() default 0;
    }

    public final class LoggingDecoratorFactoryFunction implements DecoratorFactoryFunction<LoggingDecorator> {
        @Override
        public Function<Service<HttpRequest, HttpResponse>,
                ? extends Service<HttpRequest, HttpResponse>> newDecorator(LoggingDecorator parameter) {
            return new LoggingServiceBuilder()
                    .requestLogLevel(parameter.requestLogLevel())
                    .successfulResponseLogLevel(parameter.successfulResponseLogLevel())
                    .failureResponseLogLevel(parameter.failureResponseLogLevel())
                    .samplingRate(parameter.samplingRate())
                    .newDecorator();
        }
    }

You can see `@DecoratorFactory`_ annotation at the first line of the example. It specifies a factory class
which implements DecoratorFactoryFunction_ interface. The factory will create an instance of LoggingService_
with parameters which you specified on the class or method like below.

.. code-block:: java

    public class MyAnnotatedService {
        @LoggingDecorator(requestLogLevel = LogLevel.INFO)
        @Get("/hello1")
        public HttpResponse hello1() { ... }

        @LoggingDecorator(requestLogLevel = LogLevel.DEBUG, samplingRate = 0.05)
        @Get("/hello2")
        public HttpResponse hello2() { ... }
    }

Evaluation order of decorators
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Note that the evaluation order of the decorators is slightly different from that of the converters and exception
handlers. As you read in :ref:`configure-using-serverbuilder`, both the converters and exception
handlers are applied in the order of method-level ones, class-level ones and global ones. Unlike them,
decorators are applied in the opposite order as follows, because it is more understandable for a user
to apply from the outer decorators to the inner decorators, which means the order of global decorators,
class-level decorators and method-level decorators.

.. code-block:: java

    @Decorator(MyClassDecorator2.class)                 // order 2
    @Decorator(MyClassDecorator3.class)                 // order 3
    public class MyAnnotatedService {

        @Get("/hello")
        @Decorator(MyMethodDecorator4.class)            // order 4
        @Decorator(MyMethodDecorator5.class)            // order 5
        public HttpResponse hello() { ... }
    }

    // ...

    sb.annotatedService(new MyAnnotatedService(),
                        new MyGlobalDecorator1());      // order 1

The first rule is as explained before. However, if your own decorator annotations and `@Decorator`_ annotations
are specified in a mixed order like below, you need to clearly specify their order using ``order()`` attribute
of the annotation. In the following example, you cannot make sure in what order they decorate the service
because Java collects repeatable annotations like `@Decorator`_ into a single container annotation like
`@Decorators`_ so it does not know the specified order between `@Decorator`_ and `@LoggingDecorator`_.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello")
        @Decorator(MyMethodDecorator1.class)
        @LoggingDecorator
        @Decorator(MyMethodDecorator2.class)
        public HttpResponse hello() { ... }
    }

To enforce the evaluation order of decorators, you can use ``order()`` attribute. Lower the order value is,
earlier the decorator will be executed. The default value of ``order()`` attribute is ``0``.
The ``order()`` attribute is applicable only to class-level and method-level decorators.

With the following example, the ``hello()`` will be executed with the following order:

1. ``MyGlobalDecorator1``
2. ``MyMethodDecorator1``
3. ``LoggingDecorator``
4. ``MyMethodDecorator2``
5. ``MyAnnotatedService.hello()``

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello")
        @Decorator(value = MyMethodDecorator1.class, order = 1)
        @LoggingDecorator(order = 2)
        @Decorator(value = MyMethodDecorator2.class, order = 3)
        public HttpResponse hello() { ... }
    }

    // Global-level decorators will not be affected by 'order'.
    sb.annotatedService(new MyAnnotatedService(),
                        new MyGlobalDecorator1());

Note that you can even make a method-level decorator executed before a class-level decorator
by adjusting the ``order()`` attribute:

.. code-block:: java

    @LoggingDecorator
    public class MyAnnotatedService {

        // LoggingDecorator -> MyMethodDecorator1 -> hello1()
        @Get("/hello1")
        @Decorator(MyMethodDecorator1.class)
        public HttpResponse hello1() { ... }

        // MyMethodDecorator1 -> LoggingDecorator -> hello2()
        @Get("/hello2")
        @Decorator(value = MyMethodDecorator1.class, order = -1)
        public HttpResponse hello2() { ... }
    }

If you built a custom decorator annotation like `@LoggingDecorator`_, it is recommended to
add an ``order()`` attribute so that the user of the custom annotation is able to adjust
the order value of the decorator:

.. code-block:: java

    public @interface MyDecoratorAnnotation {

        // Define your attributes.
        int myAttr1();

        // A special parameter in order to specify the order of a decorator.
        int order() default 0;
    }


.. _media_type_nego:

Media type negotiation
----------------------

Armeria provides `@ProduceType`_ and `@ConsumeType`_ annotations to support media type negotiation. It is not
necessary if you have only one service method for a path and an HTTP method. However, assume that you have
multiple service methods for the same path and the same HTTP method as follows.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello")
        public HttpResponse hello1() {
            // Return a text document to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
        }

        @Get("/hello")
        public HttpResponse hello2() {
            // Return a JSON object to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
        }
    }

If the media type is not specified on any methods bound to the same path pattern, the first method declared will
be used and the other methods will be ignored. In this example, ``hello1()`` will be chosen and the client
will always receive a text document. What if you want to get a JSON object from the path ``/hello``?
You can just specify the type of the content which your method produces as follows and add an ``Accept`` header
to your client request.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello")
        @ProduceType("text/plain")
        public HttpResponse helloText() {
            // Return a text document to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
        }

        @Get("/hello")
        @ProduceType("application/json")
        public HttpResponse helloJson() {
            // Return a JSON object to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
        }
    }

A request like the following would get a text document::

    GET /hello HTTP/1.1
    Accept: text/plain

A request like the following would get a JSON object::

    GET /hello HTTP/1.1
    Accept: application/json

.. note::

    Note that a ``Content-Type`` header of a response is not automatically set. You may want to get the
    negotiated `@ProduceType`_ from ``ServiceRequestContext.negotiatedProduceType()`` method and set it
    as the value of the ``Content-Type`` header of your response.

If a client sends a request without an ``Accept`` header (or sending an ``Accept`` header with an unsupported
content type), it would be usually mapped to ``helloJson()`` method because the methods are sorted by the
name of the type in an alphabetical order.

In this case, you can adjust the order of the methods with `@Order`_ annotation. The default value of
`@Order`_ annotation is ``0``. If you set the value less than ``0``, the method is used earlier than the
other methods, which means that it would be used as a default when there is no matched produce type.
In this example, it would also make the same effect to annotate ``helloJson()`` with ``@Order(1)``.

.. code-block:: java

    public class MyAnnotatedService {

        @Order(-1)
        @Get("/hello")
        @ProduceType("text/plain")
        public HttpResponse helloText() {
            // Return a text document to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
        }

        @Get("/hello")
        @ProduceType("application/json")
        public HttpResponse helloJson() {
            // Return a JSON object to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
        }
    }

Next, let's learn how to handle a ``Content-Type`` header of a request. Assume that there are two service
methods that expect a text document and a JSON object as a content of a request, respectively.
You can annotate them with `@ConsumeType`_ annotation.

.. code-block:: java

    public class MyAnnotatedService {

        @Post("/hello")
        @ConsumeType("text/plain")
        public HttpResponse helloText(AggregatedHttpMessage message) {
            // Get a text content by calling message.content().toStringAscii().
        }

        @Post("/hello")
        @ConsumeType("application/json")
        public HttpResponse helloJson(AggregatedHttpMessage message) {
            // Get a JSON object by calling message.content().toStringUtf8().
        }
    }

A request like the following would be handled by ``helloText()`` method::

    POST /hello HTTP/1.1
    Content-Type: text/plain
    Content-Length: 7

    Armeria

A request like the following would be handled by ``helloJson()`` method::

    POST /hello HTTP/1.1
    Content-Type: application/json
    Content-Length: 21

    { "name": "Armeria" }

However, if a client sends a request with a ``Content-Type: application/octet-stream`` header which is not
specified with `@ConsumeType`_, the client would get an HTTP status code of 415 which means
``Unsupported Media Type``. If you want to make one of the methods catch-all, you can remove the annotation
as follows. ``helloCatchAll()`` method would accept every request except for the request with a
``Content-Type: application/json`` header.

.. code-block:: java

    public class MyAnnotatedService {

        @Post("/hello")
        public HttpResponse helloCatchAll(AggregatedHttpMessage message) {
            // Get a content by calling message.content() and handle it as a text document or something else.
        }

        @Post("/hello")
        @ConsumeType("application/json")
        public HttpResponse helloJson(AggregatedHttpMessage message) {
            // Get a JSON object by calling message.content().toStringUtf8().
        }
    }

