"use strict";(self.webpackChunkarmeria_site=self.webpackChunkarmeria_site||[]).push([[7522],{91458:function(e,t,a){a.r(t),a.d(t,{_frontmatter:function(){return o},default:function(){return d}});var n,r=a(63366),i=(a(67294),a(64983)),s=a(20370),l=["components"],o={},p=(n="ThankYou",function(e){return console.warn("Component "+n+" was not imported, exported, or provided by MDXProvider as global scope"),(0,i.kt)("div",e)}),m={_frontmatter:o},u=s.Z;function d(e){var t=e.components,a=(0,r.Z)(e,l);return(0,i.kt)(u,Object.assign({},m,a,{components:t,mdxType:"MDXLayout"}),(0,i.kt)("p",{className:"date"},"17th May 2019"),(0,i.kt)("h2",{id:"new-features",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#new-features","aria-label":"new features permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"New features"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now bind your Service to a certain HTTP method or enable HTTP content-type negotiation very easily with the new ",(0,i.kt)("inlineCode",{parentName:"p"},"ServerBuilder.route()")," method. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1737"},"#1737")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'ServerBuilder sb = new ServerBuilder();\nsb.route()\n  .get("/users/{id}")\n  .delete("/users/{id}")\n  .post("/users")\n  .consumes(MediaType.JSON)\n  .produces(MediaType.JSON_UTF_8)\n  .build(new MyUserService());\n\n// You can also configure using a lambda expression.\nsb.withRoute(b -> {\n    b.path("/foo")\n     .build(new MyFooService());\n});\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"It is now also possible to specify different settings for different services using the new ",(0,i.kt)("inlineCode",{parentName:"p"},"route()")," method. It means you can specify a large timeout for a certain service only conveniently. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1737"},"#1737")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'ServerBuilder sb = new ServerBuilder();\nsb.route()\n  .path("/long_poll")\n  .requestTimeoutMillis(0) // Disable timeout for /service.\n  .build(new MyLongPollingService());\nsb.route()\n  .path("/get_now")\n  .build(new MyOtherService()); // Use the default timeout.\n```java\n\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"We revamped our ",(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders")," API to make it cleaner and safer. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1731"},"#1731")),(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders")," has been split into three types:"),(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"RequestHeaders")," for request headers with ",(0,i.kt)("inlineCode",{parentName:"li"},":method")," and ",(0,i.kt)("inlineCode",{parentName:"li"},":path")," header"),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"ResponseHeaders")," for response headers with ",(0,i.kt)("inlineCode",{parentName:"li"},":status")," header"),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"HttpHeaders")," for trailers and other headers"),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"RequestHeaders")," and ",(0,i.kt)("inlineCode",{parentName:"li"},"ResponseHeaders")," extend ",(0,i.kt)("inlineCode",{parentName:"li"},"HttpHeaders"),"."))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders")," and its subtypes are immutable and thus must be built using a factory method or a builder.")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"Quick examples:"),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'RequestHeaders reqHdrs = RequestHeaders.of(HttpMethod.GET, "/get",\n                                           HttpHeaderNames.ACCEPT, MediaType.JSON_UTF_8);\nRequestHeaders newReqHdrs = reqHdrs.toBuilder()\n                                   .add("foo", "bar")\n                                   .build();\nResponseHeaders resHdrs = ResponseHeaders.of(200 /* OK */);\n\nHttpHeaders hdrs = HttpHeaders.builder()\n                              .add("alice", "bob");\n                              .build();\nHttpHeaders newHdrs = hdrs.withMutations(builder -> {\n    builder.add("charlie", "debora");\n});\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"See ",(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders")," Javadoc for more examples.")))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now test your Armeria app with JUnit 5. A new module ",(0,i.kt)("inlineCode",{parentName:"p"},"armeria-testing-junit5")," has been added with the following extensions: ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1736"},"#1736")),(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"com.linecorp.armeria.testing.junit.server.ServerExtension")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"com.linecorp.armeria.testing.junit.server.SelfSignedCertificateExtension")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"com.linecorp.armeria.testing.junit.common.EventLoopGroupExtension")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"com.linecorp.armeria.testing.junit.common.EventLoopExtension")))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now customize the behavior of gRPC JSON marshaller. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1696"},"#1696")," ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1753"},"#1753")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"ServerBuilder sb = new ServerBuilder();\nsb.service(new GrpcServiceBuilder()\n               .addService(new MyServiceImpl())\n               .supportedSerializationFormats(GrpcSerializationFormats.values())\n               .jsonMarshallerCustomizer(marshaller -> {\n                   marshaller.preservingProtoFieldNames(true);\n               })\n               .build());\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now write a unary gRPC client without depending on grpc-java at all. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1703"},"#1703")," ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1748"},"#1748")," ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1751"},"#1751")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'HelloRequest req = HelloRequest.newBuilder()\n                               .setName("Alice")\n                               .build();\nUnaryGrpcClient client = new UnaryGrpcClient(HttpClient.of("http://127.0.0.1:8080"));\nbyte[] resBytes = client.execute("/com.example.HelloService/Greet",\n                                 req.toByteArray()).join();\nHelloResponse res = HelloResponse.parseFrom(resBytes);\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now use ",(0,i.kt)("inlineCode",{parentName:"p"},"GrpcServiceRegistrationBean")," to register a gRPC service when using Spring Boot integration. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1749"},"#1749")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},'@Bean\npublic GrpcServiceRegistrationBean helloService() {\n    return new GrpcServiceRegistrationBean()\n        .setServiceName("helloService")\n        .setService(new GrpcServiceBuilder()\n                        .addService(new HelloService())\n                        .supportedSerializationFormats(GrpcSerializationFormats.values())\n                        .enableUnframedRequests(true)\n                        .build())\n        .setDecorators(LoggingService.newDecorator())\n        .setExampleRequests(List.of(ExampleRequest.of(HelloServiceGrpc.SERVICE_NAME,\n                                                      "Hello",\n                                                      HelloRequest.newBuilder()\n                                                                  .setName("Armeria")\n                                                                  .build())));\n}\n'))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"You can now use ",(0,i.kt)("inlineCode",{parentName:"p"},"wildcard")," pattern when specifying built-in properties in Logback ",(0,i.kt)("inlineCode",{parentName:"p"},"RequestContextExportingAppender"),". ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/489"},"#489")," ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1742"},"#1742")))),(0,i.kt)("h2",{id:"bug-fixes",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#bug-fixes","aria-label":"bug fixes permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Bug fixes"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},"Trailing slashes in a path pattern is now handled correctly. ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1741"},"#1741")),(0,i.kt)("li",{parentName:"ul"},"It is now disallowed to apply ",(0,i.kt)("inlineCode",{parentName:"li"},"CorsDecorator")," more than once. ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1740"},"#1740")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"HttpTracingClient")," and ",(0,i.kt)("inlineCode",{parentName:"li"},"HttpTracingService")," now adds a valid addressable ",(0,i.kt)("inlineCode",{parentName:"li"},"http.url")," tag. ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1733"},"#1733")," ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1762"},"#1762")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"SessionProtocol")," and ",(0,i.kt)("inlineCode",{parentName:"li"},"SerializationFormat")," are now added to ",(0,i.kt)("inlineCode",{parentName:"li"},"http.protocol")," and ",(0,i.kt)("inlineCode",{parentName:"li"},"http.serfmt")," tag instead.")),(0,i.kt)("h2",{id:"breaking-changes",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#breaking-changes","aria-label":"breaking changes permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Breaking changes"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},"Artifact armeria-testing has been renamed to ",(0,i.kt)("inlineCode",{parentName:"li"},"armeria-testing-junit4"),". Please update your project dependencies. ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1736"},"#1736")),(0,i.kt)("li",{parentName:"ul"},"Many places in the public API that use ",(0,i.kt)("inlineCode",{parentName:"li"},"HttpHeaders")," as a parameter or a return value have been changed due to the revamped ",(0,i.kt)("inlineCode",{parentName:"li"},"HttpHeaders")," API. ",(0,i.kt)("a",{parentName:"li",href:"https://github.com/line/armeria/issues/1731"},"#1731")),(0,i.kt)("li",{parentName:"ul"},"The following ",(0,i.kt)("inlineCode",{parentName:"li"},"ServerBuilder")," methods were removed:",(0,i.kt)("ul",{parentName:"li"},(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"virtualHost(VirtualHost)")),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("inlineCode",{parentName:"li"},"defaultVirtualHost(VirtualHost)"))))),(0,i.kt)("h2",{id:"deprecations",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#deprecations","aria-label":"deprecations permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Deprecations"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},"The default prefix found in various configuration properties has been deprecated. Use the property setters without the default prefix. ",(0,i.kt)("a",{parentName:"p",href:"https://github.com/line/armeria/issues/1737"},"#1737")),(0,i.kt)("pre",{parentName:"li"},(0,i.kt)("code",{parentName:"pre",className:"language-java"},"ServerBuilder sb = new ServerBuilder();\n// Do NOT use this:\nsb.defaultRequestTimeout(...);\n// Use this:\nsb.requestTimeout(...);\n"))),(0,i.kt)("li",{parentName:"ul"},(0,i.kt)("p",{parentName:"li"},(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders.EMPTY")," has been deprecated. Use ",(0,i.kt)("inlineCode",{parentName:"p"},"HttpHeaders.of()"),"."))),(0,i.kt)("h2",{id:"dependencies",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#dependencies","aria-label":"dependencies permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Dependencies"),(0,i.kt)("ul",null,(0,i.kt)("li",{parentName:"ul"},"Dropwizard Metrics 4.0.5 -> 4.1.0"),(0,i.kt)("li",{parentName:"ul"},"Jetty 9.4.17 -> 9.4.18"),(0,i.kt)("li",{parentName:"ul"},"Project Reactor 3.2.8 -> 3.2.9")),(0,i.kt)("h2",{id:"thank-you",style:{position:"relative"}},(0,i.kt)("a",{parentName:"h2",href:"#thank-you","aria-label":"thank you permalink",className:"anchor before"},(0,i.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,i.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Thank you"),(0,i.kt)(p,{usernames:["anuraaga","codefromthecrypt","delegacy","ejona86","hyangtack","huydx","karthikraman22","masonshin","matsumana","minwoox","trustin"],mdxType:"ThankYou"}))}d.isMDXComponent=!0},20370:function(e,t,a){a(88025);var n=a(1923),r=a(25444),i=a(67294),s=a(55746),l=a(8284),o=a(96759),p=a(46731),m=a(9396),u=n.Z.Title,d=Object.keys(l)[0],c=h(d);function h(e){return e.substring(e.lastIndexOf("/")+1)}t.Z=function(e){var t={},a={},n={root:{"Latest news items":"/news","Latest release notes":"/release-notes","Past news items":"/news/list","Past release notes":"/release-notes/list"},"Recent news items":t,"Recent releases":a};Object.entries(s).forEach((function(e){var a=e[0],n=e[1];t[n]=a})),Object.entries(l).forEach((function(e){var t=e[0],n=e[1];a[n]=t}));var k=(0,m.Z)(e.location),N=e.version||h(k);return N.match(/^[0-9]/)||(N=void 0),i.createElement(p.Z,Object.assign({},e,{candidateMdxNodes:[],index:n,prefix:"release-notes",pageTitle:N?N+" release notes":e.pageTitle,pageTitleSuffix:"Armeria release notes"}),N&&N!==c?i.createElement(o.Ch,null,"You're seeing the release note of an old version. Check out"," ",i.createElement(r.Link,{to:d},"the latest release note"),"."):"",N?i.createElement(u,{id:"release-notes",level:1},i.createElement("a",{href:"#release-notes","aria-label":"release notes permalink",className:"anchor before"},i.createElement("svg",{"aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},i.createElement("path",{fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),N," release notes"):"",e.children)}},55746:function(e){e.exports=JSON.parse('{"/news/20231208-newsletter-6":"Armeria Newsletter vol. 6","/news/20230426-newsletter-5":"Armeria Newsletter vol. 5","/news/20220714-newsletter-4":"Armeria Newsletter vol. 4","/news/20211029-newsletter-3":"Armeria Newsletter vol. 3","/news/20210202-newsletter-2":"Armeria Newsletter vol. 2","/news/20200703-newsletter-1":"Armeria Newsletter vol. 1","/news/20200514-newsletter-0":"Armeria Newsletter vol. 0"}')},8284:function(e){e.exports=JSON.parse('{"/release-notes/1.30.2":"v1.30.2","/release-notes/1.30.1":"v1.30.1","/release-notes/1.30.0":"v1.30.0","/release-notes/1.29.4":"v1.29.4","/release-notes/1.29.3":"v1.29.3","/release-notes/1.29.2":"v1.29.2","/release-notes/1.29.1":"v1.29.1","/release-notes/1.29.0":"v1.29.0","/release-notes/1.28.4":"v1.28.4","/release-notes/1.28.3":"v1.28.3","/release-notes/1.28.2":"v1.28.2","/release-notes/1.28.1":"v1.28.1","/release-notes/1.28.0":"v1.28.0","/release-notes/1.27.3":"v1.27.3","/release-notes/1.27.2":"v1.27.2","/release-notes/1.27.1":"v1.27.1"}')}}]);
//# sourceMappingURL=component---src-pages-release-notes-0-85-0-mdx-32d2b22647d5f529f864.js.map