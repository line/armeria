import type { SidebarsConfig } from '@docusaurus/plugin-content-docs';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

/**
 * Creating a sidebar enables you to:
 - create an ordered group of docs
 - render a sidebar for each doc of that group
 - provide next/previous navigation

 The sidebars can be generated from the filesystem, or explicitly defined here.

 Create as many sidebars as you want.
 */
const sidebars: SidebarsConfig = {
  docsSidebar: [
    {
      type: 'html',
      value: 'User manual',
      defaultStyle: true,
      className: 'sidebar-title',
    },
    'index',
    'setup',
    {
      type: 'category',
      label: 'Server',
      link: {
        type: 'generated-index',
        title: 'Server',
      },
      items: [
        'server/basics',
        'server/decorator',
        'server/grpc',
        'server/thrift',
        'server/graphql',
        'server/docservice',
        'server/annotated-service',
        'server/http-file',
        'server/servlet',
        'server/access-log',
        'server/cors',
        'server/sse',
        'server/service-registration',
        'server/multipart',
        'server/timeouts',
      ],
    },
    {
      type: 'category',
      label: 'Client',
      link: {
        type: 'generated-index',
        title: 'Client',
      },
      items: [
        'client/http',
        'client/thrift',
        'client/grpc',
        'client/factory',
        'client/decorator',
        'client/retrofit',
        'client/custom-http-headers',
        'client/timeouts',
        'client/retry',
        'client/circuit-breaker',
        'client/service-discovery',
      ],
    },
    {
      type: 'category',
      label: 'Advanced',
      link: {
        type: 'generated-index',
        title: 'Advanced',
      },
      items: [
        'advanced/logging',
        'advanced/structured-logging',
        'advanced/request-context',
        'advanced/understanding-timeouts',
        'advanced/streaming-backpressure',
        'advanced/structured-logging-kafka',
        'advanced/metrics',
        'advanced/unit-testing',
        'advanced/production-checklist',
        'advanced/saml',
        'advanced/athenz',
        'advanced/spring-boot-integration',
        'advanced/spring-webflux-integration',
        'advanced/dropwizard-integration',
        'advanced/kotlin',
        'advanced/scala',
        'advanced/scalapb',
        'advanced/flags-provider',
        'advanced/zipkin',
        'advanced/client-interoperability',
      ],
    },
    {
      type: 'html',
      value: '<hr>',
      defaultStyle: true,
      className: 'sidebar-divider',
    },
    {
      type: 'html',
      value: 'Tutorials',
      defaultStyle: true,
      className: 'sidebar-title',
    },
    {
      type: 'doc',
      id: 'tutorials/index',
      label: 'Introduction',
    },
    {
      type: 'category',
      label: 'REST tutorial',
      link: {
        type: 'doc',
        id: 'tutorials/rest/index',
      },
      items: [
        'tutorials/rest/create-server',
        'tutorials/rest/prepare-data-object',
        'tutorials/rest/add-services-to-server',
        'tutorials/rest/implement-create',
        'tutorials/rest/implement-read',
        'tutorials/rest/implement-update',
        'tutorials/rest/implement-delete',
      ],
    },
    {
      type: 'category',
      label: 'gRPC tutorial',
      link: {
        type: 'doc',
        id: 'tutorials/grpc/index',
      },
      items: [
        'tutorials/grpc/define-service',
        'tutorials/grpc/run-service',
        'tutorials/grpc/implement-create',
        'tutorials/grpc/implement-read',
        'tutorials/grpc/implement-update',
        'tutorials/grpc/implement-delete',
      ],
    },
    {
      type: 'category',
      label: 'Thrift tutorial',
      link: {
        type: 'doc',
        id: 'tutorials/thrift/index',
      },
      items: [
        'tutorials/thrift/define-service',
        'tutorials/thrift/run-service',
        'tutorials/thrift/implement-create',
        'tutorials/thrift/implement-read',
        'tutorials/thrift/implement-update',
        'tutorials/thrift/implement-delete',
      ],
    },
    {
      type: 'html',
      value: '<hr>',
      defaultStyle: true,
      className: 'sidebar-divider',
    },
    {
      type: 'link',
      label: 'API documentation',
      href: 'https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/index.html',
    },
  ],
};

export default sidebars;
