.. _Publisher: https://www.reactive-streams.org/reactive-streams-1.0.2-javadoc/org/reactivestreams/Publisher.html

.. _server-annotated-service:

Annotated services
==================

.. note::

    Visit `armeria-examples <https://github.com/line/armeria-examples>`_ to find a fully working example.

Armeria provides a way to write an HTTP service using annotations. It helps a user make his or her code
simple and easy to understand. A user is able to run an HTTP service by fewer lines of code using
annotations as follows. ``hello()`` method in the example would be mapped to the path of ``/hello/{name}``
with an HTTP ``GET`` method.

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

- :api:`@Get`
- :api:`@Head`
- :api:`@Post`
- :api:`@Put`
- :api:`@Delete`
- :api:`@Options`
- :api:`@Patch`
- :api:`@Trace`

To handle an HTTP request with a service method, you can annotate your service method simply as follows.

.. code-block:: java

    public class MyAnnotatedService {
        @Get("/hello")
        public HttpResponse hello() { ... }
    }

There are 5 :api:`PathMapping` types provided for describing a path.

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
the glob pattern in your service method by annotating a parameter with :api:`@Param` as follows.
Please refer to :ref:`parameter-injection` for more information about :api:`@Param`.

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
to map more than one HTTP method to your service method? You can use :api:`@Path` annotation to specify
a path and use the HTTP method annotations without a path to map multiple HTTP methods, e.g.

.. code-block:: java

    public class MyAnnotatedService {
        @Get
        @Post
        @Put
        @Delete
        @Path("/hello")
        public HttpResponse hello() { ... }
    }

Every service method assumes that it returns an HTTP response with ``200 OK`` or ``204 No Content`` status
according to its return type. If the return type is ``void`` or ``Void``, ``204 No Content`` would be applied.
``200 OK`` would be applied for the other types. If you want to return an alternative status code for a method,
you can use :api:`@StatusCode` annotation as follows.

.. code-block:: java

    public class MyAnnotatedService {

        @StatusCode(201)
        @Post("/users/{name}")
        public User createUser(@Param("name") String name) { ... }

        // @StatusCode(200) would be applied by default.
        @Get("/users/{name}")
        public User getUser(@Param("name") String name) { ... }

        // @StatusCode(204) would be applied by default.
        @Delete("/users/{name}")
        public void deleteUser(@Param("name") String name) { ... }
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

Note that you can omit the value of :api:`@Param` if you compiled your code with ``-parameters`` javac
option. In this case the variable name is used as the value.

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
:api:`@Param` annotation. If your ``Enum`` type can be handled in a case-insensitive way, Armeria
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

When the value of :api:`@Param` annotation is not shown in the path pattern, it will be handled as a
parameter name of the query string of the request. If you have a service class like the example below and
a user sends an HTTP ``GET`` request with URI of ``/hello1?name=armeria``, the service method will get ``armeria``
as the value of parameter ``name``. If there is no parameter named ``name`` in the query string, the parameter
``name`` of the method would be ``null``. If you want to avoid ``null`` in this case, you can use
:api:`@Default` annotation or ``Optional<?>`` class, e.g. ``hello2`` and ``hello3`` methods below, respectively.

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

If multiple parameters exist with the same name in a query string, they can be injected as a ``List<?>``
or ``Set<?>``, e.g. ``/hello1?number=1&number=2&number=3``. You can use :api:`@Default` annotation
or ``Optional<?>`` class here, too.

.. code-block:: java

    public class MyAnnotatedService {
        @Get("/hello1")
        public HttpResponse hello1(@Param("number") List<Integer> numbers) { ... }

        // If there is no 'number' parameter, the default value "1" will be converted to Integer 1,
        // then it will be added to the 'numbers' list.
        @Get("/hello2")
        public HttpResponse hello2(@Param("number") @Default("1") List<Integer> numbers) { ... }

        @Get("/hello3")
        public HttpResponse hello3(@Param("number") Optional<List<Integer>> numbers) { ... }
    }

If an HTTP ``POST`` request with a ``Content-Type: x-www-form-urlencoded`` is received and no :api:`@Param`
value appears in the path pattern, Armeria will aggregate the received request and decode its body as
a URL-encoded form. After that, Armeria will inject the decoded value into the parameter.

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

Armeria also provides :api:`@Header` annotation to inject an HTTP header value into a parameter.
The parameter annotated with :api:`@Header` can also be specified as one of the built-in types as follows.
:api:`@Default` and ``Optional<?>`` are also supported. :api:`@Header` annotation also supports
``List<?>`` or ``Set<?>`` because HTTP headers can be added several times with the same name.

.. code-block:: java

    public class MyAnnotatedService {

        @Get("/hello1")
        public HttpResponse hello1(@Header("Authorization") String auth) { ... }

        @Post("/hello2")
        public HttpResponse hello2(@Header("Content-Length") long contentLength) { ... }

        @Post("/hello3")
        public HttpResponse hello3(@Header("Forwarded") List<String> forwarded) { ... }

        @Post("/hello4")
        public HttpResponse hello4(@Header("Forwarded") Optional<Set<String>> forwarded) { ... }
    }

Note that you can omit the value of :api:`@Header` if you compiled your code with ``-parameters`` javac
option. Read :ref:`parameter-injection` for more information.
In this case, the variable name is used as the value, but it will be converted to hyphen-separated lowercase
string to be suitable for general HTTP header names. e.g. a variable name ``contentLength`` or
``content_length`` will be converted to ``content-length`` as the value of :api:`@Header`.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello2")
        public HttpResponse hello2(@Header long contentLength) { ... }
    }

Other classes automatically injected
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

The following classes are automatically injected when you specify them on the parameter list of your method.

- :api:`RequestContext`
- :api:`ServiceRequestContext`
- :api:`Request`
- :api:`HttpRequest`
- :api:`AggregatedHttpMessage`
- :api:`HttpParameters`
- :api:`Cookies`

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

        @Post("/hello5")
        public HttpResponse hello5(Cookies cookies) {
            // If 'Cookie' header exists, it will be injected into the specified 'cookies' parameter.
        }
    }

Handling exceptions
-------------------

It is often useful to extract exception handling logic from service methods into a separate common class.
Armeria provides :api:`@ExceptionHandler` annotation to transform an exception into a response.
You can write your own exception handler by implementing :api:`ExceptionHandlerFunction` interface and
annotate your service object or method with :api:`@ExceptionHandler` annotation. Here is an example of
an exception handler. If your exception handler is not able to handle a given exception, you can call
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
the default exception handler. It handles ``IllegalArgumentException``, :api:`HttpStatusException` and
:api:`HttpResponseException` by default. ``IllegalArgumentException`` would be converted into
``400 Bad Request`` response, and the other two exceptions would be converted into a response with
the status code which they are holding. For another exceptions, ``500 Internal Server Error`` would be
sent to the client.

Conversion between an HTTP message and a Java object
----------------------------------------------------

Converting an HTTP request to a Java object
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

In some cases like receiving a JSON document from a client, it may be useful to convert the document to
a Java object automatically. Armeria provides :api:`@RequestConverter` and :api:`@RequestObject`
annotations so that such conversion can be done conveniently.
You can write your own request converter by implementing :api:`RequestConverterFunction` as follows.
Similar to the exception handler, you can call ``RequestConverterFunction.fallthrough()`` when your request
converter is not able to convert the request.

.. code-block:: java

    public class ToEnglishConverter implements RequestConverterFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) {
            if (expectedResultType == Greeting.class) {
                // Convert the request to a Java object.
                return new Greeting(translateToEnglish(request.contentUtf8()));
            }

            // To the next request converter.
            return RequestConverterFunction.fallthrough();
        }

        private String translateToEnglish(String greetingInAnyLanguage) { ... }
    }

Then, you can write your service method as follows. Custom request objects will be converted automatically
by the converters you registered with :api:`@RequestConverter` annotation. Note that :api:`@RequestConverter`
annotation can be specified on a class, a method or a parameter in an annotated service, and its scope
is determined depending on where it is specified.

.. code-block:: java

    @RequestConverter(ToEnglishConverter.class)
    public class MyAnnotatedService {

        @Post("/hello")
        public HttpResponse hello(Greeting greeting) {
            // ToEnglishConverter will be used to convert a request.
            // ...
        }

        @Post("/hola")
        @RequestConverter(ToSpanishConverter.class)
        public HttpResponse hola(Greeting greeting) {
            // ToSpanishConverter will be tried to convert a request first.
            // ToEnglishConverter will be used if ToSpanishConverter fell through.
            // ...
        }

        @Post("/greet")
        public HttpResponse greet(RequestConverter(ToGermanConverter.class) Greeting greetingInGerman,
                                  Greeting greetingInEnglish) {
            // For the 1st parameter 'greetingInGerman':
            // ToGermanConverter will be tried to convert a request first.
            // ToEnglishConverter will be used if ToGermanConverter fell through.
            //
            // For the 2nd parameter 'greetingInEnglish':
            // ToEnglishConverter will be used to convert a request.
            // ...
        }
    }

Armeria also provides built-in request converters such as, a request converter for a Java Bean,
:api:`JacksonRequestConverterFunction` for a JSON document, :api:`StringRequestConverterFunction`
for a string and :api:`ByteArrayRequestConverterFunction` for binary data. They will be applied
after your request converters, so you don't have to specify any :api:`@RequestConverter` annotations:

.. code-block:: java

    public class MyAnnotatedService {

        // JacksonRequestConverterFunction will work for the content type of 'application/json' or
        // one of '+json' types.
        @Post("/hello1")
        public HttpResponse hello1(JsonNode body) { ... }

        @Post("/hello2")
        public HttpResponse hello2(MyJsonRequest body) { ... }

        // StringRequestConverterFunction will work for the content type of any of 'text'.
        @Post("/hello3")
        public HttpResponse hello3(String body) { ... }

        @Post("/hello4")
        public HttpResponse hello4(CharSequence body) { ... }

        // ByteArrayRequestConverterFunction will work for the content type of 'application/octet-stream',
        // 'application/binary' or none.
        @Post("/hello5")
        public HttpResponse hello5(byte[] body) { ... }

        @Post("/hello6")
        public HttpResponse hello6(HttpData body) { ... }
    }

Injecting value of parameters and HTTP headers into a Java object
"""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""""

Armeria provides a generic built-in request converter that converts a request into a Java object.
Just define a plain old Java class and specify it as a parameter of your service method.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello")
        public HttpResponse hello(MyRequestObject myRequestObject) { ... }
    }



We also need to define the ``MyRequestObject`` class which was used in the method ``hello()`` above.
To tell Armeria which constructor parameter, setter method or field has to be injected with what value,
we should put :api:`@Param`, :api:`@Header`, :api:`@RequestObject` annotations on any of the following elements:

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

        @RequestObject // This field will be injected by another request converter.
        private MyAnotherRequestObject obj;

        // You can omit the value of @Param or @Header if you compiled your code with ``-parameters`` javac option.
        @Param         // This field will be injected by the value of parameter "gender".
        private String gender;

        @Header        // This field will be injected by the value of HTTP header "accept-language".
        private String acceptLanguage;

        @Param("address") // You can annotate a single parameter method with @Param or @Header.
        public void setAddress(String address) { ... }

        @Header("id") // You can annotate a single parameter constructor with @Param or @Header.
        @Default("0")
        public MyRequestObject(long id) { ... }

        // You can annotate all parameters of method or constructor with @Param or @Header.
        public void init(@Header("permissions") String permissions,
                         @Param("client-id") @Default("0") int clientId)
    }

The usage of :api:`@Param` or :api:`@Header` annotations on Java object elements is much like
using them on the parameters of a service method because even you can use :api:`@Default` and
:api:`@RequestObject` annotations defined there.
Please refer to :ref:`parameter-injection`, and :ref:`header-injection` for more information.

.. _response_converter:

Converting a Java object to an HTTP response
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Every object returned by an annotated service method can be converted to an HTTP response message by
response converters, except for :api:`HttpResponse` and :api:`AggregatedHttpMessage` which are already
in a form of response message. You can also write your own response converter by implementing
:api:`ResponseConverterFunction` as follows. Also similar to :api:`RequestConverterFunction`,
you can call ``ResponseConverterFunction.fallthrough()`` when your response converter is not able to
convert the result to an :api:`HttpResponse`.

.. code-block:: java

    public class MyResponseConverter implements ResponseConverterFunction {
        @Override
        HttpResponse convertResponse(ServiceRequestContext ctx,
                                     HttpHeaders headers,
                                     @Nullable Object result,
                                     HttpHeaders trailingHeaders) throws Exception {
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
        HttpResponse convertResponse(ServiceRequestContext ctx,
                                     HttpHeaders headers,
                                     @Nullable Object result,
                                     HttpHeaders trailingHeaders) throws Exception {
            MediaType mediaType = ctx.negotiatedResponseMediaType();
            if (mediaType != null) {
                // Do something based on the media type.
                // ...
            }
        }
    }

Even if you do not specify any :api:`ResponseConverter` annotation, the response object can be converted into
an :api:`HttpResponse` by one of the following response converters which performs the conversion based on
the negotiated media type and the type of the object.

- :api:`JacksonResponseConverterFunction`

  - converts an object to a JSON document if the negotiated media type is ``application/json``.
    ``JsonNode`` object can be converted to a JSON document even if there is no media type negotiated.

- :api:`StringResponseConverterFunction`

  - converts an object to a string if the negotiated main media type is one of ``text`` types.
    If there is no media type negotiated, ``String`` and ``CharSequence`` object will be written as a text
    with ``Content-Type: text/plain; charset=utf-8`` header.

- :api:`ByteArrayResponseConverterFunction`

  - converts an object to a byte array. Only :api:`HttpData` and ``byte[]`` will be handled
    even if the negotiated media type is ``application/binary`` or ``application/octet-stream``.
    If there is no media type negotiated, :api:`HttpData` and ``byte[]`` object will be written as a binary
    with ``Content-Type: application/binary`` header.

Let's see the following example about the default response conversion.

.. code-block:: java

    public class MyAnnotatedService {

        // JacksonResponseConverterFunction will convert the return values to JSON documents:
        @Get("/json1")
        @ProducesJson    // the same as @Produces("application/json; charset=utf-8")
        public MyObject json1() { ... }

        @Get("/json2")
        public JsonNode json2() { ... }

        // StringResponseConverterFunction will convert the return values to strings:
        @Get("/string1")
        @ProducesText    // the same as @Produces("text/plain; charset=utf-8")
        public int string1() { ... }

        @Get("/string2")
        public CharSequence string2() { ... }

        // ByteArrayResponseConverterFunction will convert the return values to byte arrays:
        @Get("/byte1")
        @ProducesBinary  // the same as @Produces("application/binary")
        public HttpData byte1() { ... }

        @Get("/byte2")
        public byte[] byte2() { ... }
    }

.. _configure-using-serverbuilder:

Using ``ServerBuilder`` to configure converters and exception handlers
----------------------------------------------------------------------

You can specify converters and exception handlers using :api:`ServerBuilder`, without using the annotations
explained in the previous sections:

.. code-block:: java

    sb.annotatedService(new MyAnnotatedService(),
                        new MyExceptionHandler(), new MyRequestConverter(), new MyResponseConverter());

Also, they have a different method signature for conversion and exception handling so you can even write them
in a single class and add it to your :api:`ServerBuilder` at once, e.g.

.. code-block:: java

    public class MyAllInOneHandler implements RequestConverterFunction,
                                              ResponseConverterFunction,
                                              ExceptionHandlerFunction {
        @Override
        public Object convertRequest(ServiceRequestContext ctx, AggregatedHttpMessage request,
                                     Class<?> expectedResultType) { ... }

        @Override
        HttpResponse convertResponse(ServiceRequestContext ctx,
                                     HttpHeaders headers,
                                     @Nullable Object result,
                                     HttpHeaders trailingHeaders) throws Exception { ... }

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

Returning a response
--------------------

In the earlier examples, the annotated service methods only return :api:`HttpResponse`, however there are
more response types which can be used in the annotated service.

- :api:`HttpResponse` and :api:`AggregatedHttpMessage`

  - It will be sent to the client without any modification. If an exception is raised while the response is
    being sent, exception handlers will handle it. If no message has been sent to the client yet,
    the exception handler can send an :api:`HttpResponse` instead.

- :api:`HttpResult`

  - It contains the :api:`HttpHeaders` and the object which can be converted into HTTP response body by
    response converters. A user can customize the HTTP status and headers including the trailing headers,
    with this type.

  .. code-block:: java

      public class MyAnnotatedService {
          @Get("/users")
          public HttpResult<User> getUsers(@Param int start) {
              List<User> users = ...;
              HttpHeaders headers = HttpHeaders.of(HttpStatus.OK);
              headers.add(HttpHeaderNames.LINK,
                          String.format("<https://example.com/users?start=%s>; rel=\"next\"", start + 10));
              return HttpResult.of(headers, users);
          }

- Reactive Streams Publisher_

  - All objects which are produced by the publisher will be collected, then the collected ones will be
    converted to an :api:`HttpResponse` by response converters. If a single object is produced, it will be
    passed into the response converters as it is. But if multiple objects are produced, they will be passed
    into the response converters as a list. If the producer produces an error, exception handlers will handle it.
    Note that RxJava `ObservableSource <http://reactivex.io/RxJava/javadoc/io/reactivex/ObservableSource.html>`_
    will be treated in the same way as Publisher_ if you add ``armeria-rxjava`` to the dependencies.

- ``CompletionStage`` and ``CompletableFuture``

  - An object which is generated by the ``CompletionStage`` will be converted to an :api:`HttpResponse`
    by response converters. If the ``CompletionStage`` completes exceptionally, exception handlers will
    handle the cause.

- Other types

  - As described in :ref:`response_converter`, you can use any response types with response converters
    that convert them. If a service method raises an exception, exception handlers will handle it.

Decorating an annotated service
-------------------------------

Every :api:`Service` can be wrapped by another :api:`Service` in Armeria (Refer to :ref:`server-decorator`
for more information). Simply, you can write your own decorator by implementing :api:`DecoratingServiceFunction`
interface as follows.

.. code-block:: java

    public class MyDecorator implements DecoratingServiceFunction<HttpRequest, HttpResponse> {
        @Override
        public HttpResponse serve(Service<HttpRequest, HttpResponse> delegate,
                                  ServiceRequestContext ctx, HttpRequest req) {
            // ... Do something ...
            return delegate.serve(ctx, req);
        }
    }

Then, annotate your class or method with a :api:`@Decorator` annotation. In the following example,
``MyDecorator`` will handle a request first, then ``AnotherDecorator`` will handle the request next,
and finally ``hello()`` method will handle the request.

.. code-block:: java

    @Decorator(MyDecorator.class)
    public class MyAnnotatedService {
        @Decorator(AnotherDecorator.class)
        @Get("/hello")
        public HttpResponse hello() { ... }
    }

Decorating an annotated service with a custom decorator annotation
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

As you read earlier, you can write your own decorator with :api:`DecoratingServiceFunction` interface.
If your decorator does not require any parameter, that is fine. However, what if your decorator requires
a parameter? In this case, you can create your own decorator annotation. Let's see the following custom
decorator annotation which applies :api:`LoggingService` to an annotated service.

.. note::

    This example is actually just a copy of what Armeria provides out of the box. In reality,
    you could just use :api:`@LoggingDecorator`, without writing your own one.

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

You can see :api:`@DecoratorFactory` annotation at the first line of the example. It specifies
a factory class which implements :api:`DecoratorFactoryFunction` interface. The factory will create
an instance of :api:`LoggingService` with parameters which you specified on the class or method like below.

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

The first rule is as explained before. However, if your own decorator annotations and :api:`@Decorator`
annotations are specified in a mixed order like below, you need to clearly specify their order using ``order()``
attribute of the annotation. In the following example, you cannot make sure in what order they decorate
the service because Java collects repeatable annotations like :api:`@Decorator` into a single container
annotation like :api:`@Decorators` so it does not know the specified order between :api:`@Decorator`
and :api:`@LoggingDecorator`.

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

If you built a custom decorator annotation like :api:`@LoggingDecorator`, it is recommended to
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

Armeria provides :api:`@Produces` and :api:`@Consumes` annotations to support media type
negotiation. It is not necessary if you have only one service method for a path and an HTTP method.
However, assume that you have multiple service methods for the same path and the same HTTP method as follows.

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
        @Produces("text/plain")
        public HttpResponse helloText() {
            // Return a text document to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
        }

        @Get("/hello")
        @Produces("application/json")
        public HttpResponse helloJson() {
            // Return a JSON object to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
        }
    }

A request like the following would get a text document:

.. code-block:: http

    GET /hello HTTP/1.1
    Accept: text/plain

A request like the following would get a JSON object:

.. code-block:: http

    GET /hello HTTP/1.1
    Accept: application/json

.. note::

    Note that a ``Content-Type`` header of a response is not automatically set. You may want to get the
    negotiated :api:`@Produces` from ``ServiceRequestContext.negotiatedResponseMediaType()`` method and
    set it as the value of the ``Content-Type`` header of your response.

If a client sends a request without an ``Accept`` header (or sending an ``Accept`` header with an unsupported
content type), it would be usually mapped to ``helloJson()`` method because the methods are sorted by the
name of the type in an alphabetical order.

In this case, you can adjust the order of the methods with :api:`@Order` annotation. The default value of
:api:`@Order` annotation is ``0``. If you set the value less than ``0``, the method is used earlier than
the other methods, which means that it would be used as a default when there is no matched produce type.
In this example, it would also make the same effect to annotate ``helloJson()`` with ``@Order(1)``.

.. code-block:: java

    public class MyAnnotatedService {

        @Order(-1)
        @Get("/hello")
        @Produces("text/plain")
        public HttpResponse helloText() {
            // Return a text document to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Armeria");
        }

        @Get("/hello")
        @Produces("application/json")
        public HttpResponse helloJson() {
            // Return a JSON object to the client.
            return HttpResponse.of(HttpStatus.OK, MediaType.JSON_UTF_8, "{ \"name\": \"Armeria\" }");
        }
    }

Next, let's learn how to handle a ``Content-Type`` header of a request. Assume that there are two service
methods that expect a text document and a JSON object as a content of a request, respectively.
You can annotate them with :api:`@Consumes` annotation.

.. code-block:: java

    public class MyAnnotatedService {

        @Post("/hello")
        @Consumes("text/plain")
        public HttpResponse helloText(AggregatedHttpMessage message) {
            // Get a text content by calling message.contentAscii().
        }

        @Post("/hello")
        @Consumes("application/json")
        public HttpResponse helloJson(AggregatedHttpMessage message) {
            // Get a JSON object by calling message.contentUtf8().
        }
    }

A request like the following would be handled by ``helloText()`` method:

.. code-block:: http

    POST /hello HTTP/1.1
    Content-Type: text/plain
    Content-Length: 7

    Armeria

A request like the following would be handled by ``helloJson()`` method:

.. code-block:: http

    POST /hello HTTP/1.1
    Content-Type: application/json
    Content-Length: 21

    { "name": "Armeria" }

However, if a client sends a request with a ``Content-Type: application/octet-stream`` header which is not
specified with :api:`@Consumes`, the client would get an HTTP status code of 415 which means
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
        @Consumes("application/json")
        public HttpResponse helloJson(AggregatedHttpMessage message) {
            // Get a JSON object by calling message.contentUtf8().
        }
    }

Creating user-defined media type annotations
^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^

Armeria provides pre-defined annotations such as :api:`@ConsumesJson`, :api:`@ConsumesText`,
:api:`@ConsumesBinary` and :api:`@ConsumesOctetStream` which are aliases for
``@Consumes("application/json; charset=utf-8")``, ``@Consumes("text/plain; charset=utf-8")``,
``@Consumes("application/binary")`` and ``@Consumes("application/octet-stream")`` respectively.
Also, :api:`@ProducesJson`, :api:`@ProducesText`, :api:`@ProducesBinary` and :api:`@ProducesOctetStream`
are provided in the same manner.

If there is no annotation that meets your need, you can define your own annotations for :api:`@Consumes`
and :api:`@Produces` as follows. Specifying your own annotations is recommended because writing a media type
with a string is more error-prone.

.. code-block:: java

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Consumes("application/xml")
    public @interface MyConsumableType {}

    @Retention(RetentionPolicy.RUNTIME)
    @Target({ ElementType.TYPE, ElementType.METHOD })
    @Produces("application/xml")
    public @interface MyProducibleType {}

Then, you can annotate your service method with your annotation as follows.

.. code-block:: java

    public class MyAnnotatedService {
        @Post("/hello")
        @MyConsumableType  // the same as @Consumes("application/xml")
        @MyProducibleType  // the same as @Produces("application/xml")
        public MyResponse hello(MyRequest myRequest) { ... }
    }


Specifying additional response headers/trailers
-----------------------------------------------

Armeria provides a way to configure additional headers/trailers via annotation,
:api:`@AdditionalHeader` for HTTP headers and :api:`@AdditionalTrailer` for HTTP trailers.

You can annotate your service method with the annotations as follows.

.. code-block:: java

    import com.linecorp.armeria.server.annotation.AdditionalHeader;
    import com.linecorp.armeria.server.annotation.AdditionalTrailer;

    @AdditionalHeader(name = "custom-header", value = "custom-value")
    @AdditionalTrailer(name = "custom-trailer", value = "custom-value")
    public class MyAnnotatedService {
        @Get("/hello")
        @AdditionalHeader(name = "custom-header-2", value = "custom-value")
        @AdditionalTrailer(name = "custom-trailer-2", value = "custom-value")
        public HttpResponse hello() { ... }
    }

The :api:`@AdditionalHeader` or :api:`@AdditionalTrailer` specified at the method level takes precedence over
what's specified at the class level if it has the same name, e.g.

.. code-block:: java

    @AdditionalHeader(name = "custom-header", value = "custom-value")
    @AdditionalTrailer(name = "custom-trailer", value = "custom-value")
    public class MyAnnotatedService {
        @Get("/hello")
        @AdditionalHeader(name = "custom-header", value = "custom-overwritten")
        @AdditionalTrailer(name = "custom-trailer", value = "custom-overwritten")
        public HttpResponse hello() { ... }
    }

In this case, the values of the HTTP header named ``custom-header`` and the HTTP trailer named
``custom-trailer`` will be ``custom-overwritten``, not ``custom-value``.

Note that the trailers will not be injected into the responses with the following HTTP status code,
because they always have an empty content.

+--------------+----------------+
| Status code  | Description    |
+==============+================+
| 1xx          | Informational  |
+--------------+----------------+
| 204          | No content     |
+--------------+----------------+
| 205          | Reset content  |
+--------------+----------------+
| 304          | Not modified   |
+--------------+----------------+

