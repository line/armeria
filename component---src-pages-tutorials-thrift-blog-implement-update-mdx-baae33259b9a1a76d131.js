"use strict";(self.webpackChunkarmeria_site=self.webpackChunkarmeria_site||[]).push([[2886],{30265:function(e,t,a){a.r(t),a.d(t,{_frontmatter:function(){return p},default:function(){return h},pageTitle:function(){return s}});var n,i=a(63366),l=(a(67294),a(64983)),r=a(89791),o=["components"],s="Implementing UPDATE operation",p={},c=(n="TutorialSteps",function(e){return console.warn("Component "+n+" was not imported, exported, or provided by MDXProvider as global scope"),(0,l.kt)("div",e)}),d={pageTitle:s,_frontmatter:p},m=r.Z;function h(e){var t=e.components,a=(0,i.Z)(e,o);return(0,l.kt)(m,Object.assign({},d,a,{components:t,mdxType:"MDXLayout"}),(0,l.kt)("h1",{id:"implementing-update-operation",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h1",href:"#implementing-update-operation","aria-label":"implementing update operation permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Implementing UPDATE operation"),(0,l.kt)("h6",{className:"inlinePageToc",role:"navigation"},"Table of contents"),(0,l.kt)("ul",null,(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#what-you-need"},"What you need"))),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#1-implement-server-side"},"1. Implement server-side")),(0,l.kt)("ul",{parentName:"li"},(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("a",{parentName:"li",href:"#add-an-exception-handler"},"Add an exception handler")),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("a",{parentName:"li",href:"#implement-the-service-method"},"Implement the service method")))),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#2-implement-client-side"},"2. Implement client-side"))),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#3-test-updating-a-blog-post"},"3. Test updating a blog post"))),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#4-test-an-error-case"},"4. Test an error case"))),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("p",{parentName:"li"},(0,l.kt)("a",{parentName:"p",href:"#whats-next"},"What's next")))),(0,l.kt)("p",null,"Previously, we created and read blog posts.\nNow, let's implement and make a call to update a blog post.\nWe'll also learn how to handle an exception with a custom exception handler."),(0,l.kt)(c,{current:5,mdxType:"TutorialSteps"}),(0,l.kt)("h2",{id:"what-you-need",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#what-you-need","aria-label":"what you need permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"What you need"),(0,l.kt)("p",null,"You need to have the following files obtained from previous steps.\nYou can always ",(0,l.kt)("a",{parentName:"p",href:"https://github.com/line/armeria-examples/tree/main/tutorials/thrift"},"download")," the full version, instead of creating one yourself."),(0,l.kt)("ul",null,(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("a",{parentName:"li",href:"/tutorials/thrift/blog/define-service#3-compile-the-thrift-file"},"Generated Java code")),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("inlineCode",{parentName:"li"},"BlogServiceImpl.java")),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("inlineCode",{parentName:"li"},"Main.java")),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("inlineCode",{parentName:"li"},"BlogClient.java")),(0,l.kt)("li",{parentName:"ul"},(0,l.kt)("inlineCode",{parentName:"li"},"BlogServiceTest.java"))),(0,l.kt)("h2",{id:"1-implement-server-side",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#1-implement-server-side","aria-label":"1 implement server side permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"1. Implement server-side"),(0,l.kt)("p",null,"Let's implement the server-side for updating blog posts.\nThis time, we'll use a custom exception handler."),(0,l.kt)("h3",{id:"add-an-exception-handler",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h3",href:"#add-an-exception-handler","aria-label":"add an exception handler permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Add an exception handler"),(0,l.kt)("p",null,"First, add a custom exception handler for the blog service."),(0,l.kt)("ol",null,(0,l.kt)("li",{parentName:"ol"},(0,l.kt)("p",{parentName:"li"},"Add an exception handler class to convert an ",(0,l.kt)("inlineCode",{parentName:"p"},"IllegalArgumentException")," into a ",(0,l.kt)("inlineCode",{parentName:"p"},"BlogNotFoundException"),"."),(0,l.kt)("pre",{parentName:"li"},(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogServiceExceptionHandler.java",filename:"BlogServiceExceptionHandler.java"},"package example.armeria.server.blog.thrift;\n\nimport java.util.function.BiFunction;\n\nimport com.linecorp.armeria.common.RpcResponse;\nimport com.linecorp.armeria.server.ServiceRequestContext;\n\nimport example.armeria.blog.thrift.BlogNotFoundException;\n\npublic class BlogServiceExceptionHandler implements BiFunction<ServiceRequestContext, Throwable, RpcResponse> {\n\n  @Override\n  public RpcResponse apply(ServiceRequestContext serviceRequestContext, Throwable cause) {\n    if (cause instanceof IllegalArgumentException) {\n      return RpcResponse.ofFailure(new BlogNotFoundException(cause.getMessage()));\n    }\n    return RpcResponse.ofFailure(cause);\n  }\n}\n"))),(0,l.kt)("li",{parentName:"ol"},(0,l.kt)("p",{parentName:"li"},"In the ",(0,l.kt)("inlineCode",{parentName:"p"},"Main")," class, bind the ",(0,l.kt)("inlineCode",{parentName:"p"},"BlogServiceExceptionHandler")," to our service."),(0,l.kt)("pre",{parentName:"li"},(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=Main.java",filename:"Main.java"},"...\nprivate static Server newServer(int port) throws Exception {\n  final THttpService tHttpService =\n    THttpService.builder()\n                .addService(new BlogServiceImpl())\n                .exceptionHandler(new BlogServiceExceptionHandler()) // Add this\n                .build();\n  ...\n}\n")))),(0,l.kt)("h3",{id:"implement-the-service-method",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h3",href:"#implement-the-service-method","aria-label":"implement the service method permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"Implement the service method"),(0,l.kt)("p",null,"In the ",(0,l.kt)("inlineCode",{parentName:"p"},"BlogServiceImpl")," class, implement the ",(0,l.kt)("inlineCode",{parentName:"p"},"updateBlogPost()")," method to update a blog post.\nThis time, let's use the ",(0,l.kt)("inlineCode",{parentName:"p"},"IllegalArgumentException")," instead of the ",(0,l.kt)("inlineCode",{parentName:"p"},"BlogNotFoundException"),"."),(0,l.kt)("pre",null,(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogServiceImpl.java",filename:"BlogServiceImpl.java"},'@Override\npublic void updateBlogPost(UpdateBlogPostRequest request, AsyncMethodCallback<BlogPost> resultHandler)\n        throws TException {\n  final BlogPost oldBlogPost = blogPosts.get(request.getId());\n  if (oldBlogPost == null) {\n    resultHandler.onError(\n            new IllegalArgumentException("The blog post does not exist. ID: " + request.getId()));\n  } else {\n    final BlogPost newBlogPost = oldBlogPost\n            .deepCopy()\n            .setTitle(request.getTitle())\n            .setContent(request.getContent())\n            .setModifiedAt(Instant.now().toEpochMilli());\n    blogPosts.put(request.getId(), newBlogPost);\n    resultHandler.onComplete(newBlogPost);\n  }\n}\n')),(0,l.kt)("h2",{id:"2-implement-client-side",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#2-implement-client-side","aria-label":"2 implement client side permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"2. Implement client-side"),(0,l.kt)("p",null,"Add a method ",(0,l.kt)("inlineCode",{parentName:"p"},"updateBlogPost()")," to send a request to update a blog post."),(0,l.kt)("pre",null,(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogClient.java",filename:"BlogClient.java"},"import example.armeria.blog.thrift.UpdateBlogPostRequest;\n...\nBlogPost updateBlogPost(int id, String newTitle, String newContent) throws TException {\n  final UpdateBlogPostRequest request = new UpdateBlogPostRequest().setId(id).setTitle(newTitle).setContent(newContent);\n  return blogService.updateBlogPost(request);\n}\n")),(0,l.kt)("h2",{id:"3-test-updating-a-blog-post",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#3-test-updating-a-blog-post","aria-label":"3 test updating a blog post permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"3. Test updating a blog post"),(0,l.kt)("p",null,"Let's try updating the content of the first blog post.\nAdd a method like the following."),(0,l.kt)("pre",null,(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogServiceTest.java",filename:"BlogServiceTest.java"},'@Test\n@Order(5)\nvoid updateBlogPosts() throws TException {\n  final BlogClient client = new BlogClient(server.httpUri(), "/thrift");\n  final BlogPost updated = client.updateBlogPost(0, "My first blog", "Hello awesome Armeria!");\n  assertThat(updated.getId()).isZero();\n  assertThat(updated.getTitle()).isEqualTo("My first blog");\n  assertThat(updated.getContent()).isEqualTo("Hello awesome Armeria!");\n}\n')),(0,l.kt)("p",null,"Run all the test cases on your IDE or using Gradle.\nCheck that you see the test is passed."),(0,l.kt)("h2",{id:"4-test-an-error-case",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#4-test-an-error-case","aria-label":"4 test an error case permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"4. Test an error case"),(0,l.kt)("p",null,"To check that our exception handler is working, let's try updating a post which does not exist."),(0,l.kt)("ol",null,(0,l.kt)("li",{parentName:"ol"},"Bind the exception handler to the service for the test server.",(0,l.kt)("pre",{parentName:"li"},(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogServiceTest.java",filename:"BlogServiceTest.java"},'@RegisterExtension\nstatic final ServerExtension server = new ServerExtension() {\n  @Override\n  protected void configure(ServerBuilder sb) throws Exception {\n    sb.service("/thrift", THttpService.builder()\n        .exceptionHandler(new BlogServiceExceptionHandler()) // Add this\n        .addService(new BlogServiceImpl())\n        .build());\n  }\n};\n'))),(0,l.kt)("li",{parentName:"ol"},"Add a test method to update a blog post with an invalid ID, asserting a ",(0,l.kt)("inlineCode",{parentName:"li"},"BlogNotFoundException")," is thrown.",(0,l.kt)("pre",{parentName:"li"},(0,l.kt)("code",{parentName:"pre",className:"language-java",metastring:"filename=BlogServiceTest.java",filename:"BlogServiceTest.java"},'@Test\n@Order(6)\nvoid updateInvalidBlogPost() {\n  final BlogClient client = new BlogClient(server.httpUri(), "/thrift");\n  final Throwable exception = catchThrowable(() -> {\n    final BlogPost updated = client.updateBlogPost(Integer.MAX_VALUE, "My first blog", "Hello awesome Armeria!");\n  });\n  assertThat(exception)\n    .isInstanceOf(BlogNotFoundException.class)\n    .extracting("reason")\n    .asString()\n    .isEqualTo("The blog post does not exist. ID: " + Integer.MAX_VALUE);\n}\n'))),(0,l.kt)("li",{parentName:"ol"},"Run all the test cases on your IDE or using Gradle.\nCheck that you see the test is passed.")),(0,l.kt)("h2",{id:"whats-next",style:{position:"relative"}},(0,l.kt)("a",{parentName:"h2",href:"#whats-next","aria-label":"whats next permalink",className:"anchor before"},(0,l.kt)("svg",{parentName:"a","aria-hidden":"true",focusable:"false",height:"16",version:"1.1",viewBox:"0 0 16 16",width:"16"},(0,l.kt)("path",{parentName:"svg",fillRule:"evenodd",d:"M4 9h1v1H4c-1.5 0-3-1.69-3-3.5S2.55 3 4 3h4c1.45 0 3 1.69 3 3.5 0 1.41-.91 2.72-2 3.25V8.59c.58-.45 1-1.27 1-2.09C10 5.22 8.98 4 8 4H4c-.98 0-2 1.22-2 2.5S3 9 4 9zm9-3h-1v1h1c1 0 2 1.22 2 2.5S13.98 12 13 12H9c-.98 0-2-1.22-2-2.5 0-.83.42-1.64 1-2.09V6.25c-1.09.53-2 1.84-2 3.25C6 11.31 7.55 13 9 13h4c1.45 0 3-1.69 3-3.5S14.5 6 13 6z"}))),"What's next"),(0,l.kt)("p",null,"In this step, we've implemented a service method and client method for updating a blog post.\nWe've also added an exception handler."),(0,l.kt)("p",null,"Next, at ",(0,l.kt)("a",{parentName:"p",href:"/tutorials/thrift/blog/implement-delete"},"Step 6. Implement DELETE"),", we'll implement a method\nfor deleting a blog post and add a ",(0,l.kt)("a",{parentName:"p",href:"/docs/server-docservice"},"Documentation Service")," to our service."),(0,l.kt)(c,{current:5,mdxType:"TutorialSteps"}))}h.isMDXComponent=!0},89791:function(e,t,a){a.d(t,{Z:function(){return o}});var n=a(25444),i=a(67294),l=JSON.parse('{"root":["index"],"Useful links":{"User manual":"/docs","API documentation":"https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/index.html","Release notes":"/release-notes"},"REST service":["rest/blog","rest/blog/create-server","rest/blog/prepare-data-object","rest/blog/add-services-to-server","rest/blog/implement-create","rest/blog/implement-read","rest/blog/implement-update","rest/blog/implement-delete"],"gRPC service":["grpc/blog","grpc/blog/define-service","grpc/blog/run-service","grpc/blog/implement-create","grpc/blog/implement-read","grpc/blog/implement-update","grpc/blog/implement-delete"],"Thrift service":["thrift/blog","thrift/blog/define-service","thrift/blog/run-service","thrift/blog/implement-create","thrift/blog/implement-read","thrift/blog/implement-update","thrift/blog/implement-delete"]}'),r=a(46731),o=function(e){var t=(0,n.useStaticQuery)("3172452987").allMdx.nodes;return i.createElement(r.Z,Object.assign({},e,{candidateMdxNodes:t,index:l,prefix:"tutorials",menuTitle:!0,pageTitleSuffix:"Armeria tutorial"}))}}}]);
//# sourceMappingURL=component---src-pages-tutorials-thrift-blog-implement-update-mdx-baae33259b9a1a76d131.js.map