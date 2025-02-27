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
  documentationSideBar: [
    {
      type: 'html',
      value: 'User manual',
      defaultStyle: true, // TODO: Styling - bold, small, capital letters
    },
    'index',
    'setup',
    {
      type: 'category',
      label: 'Server',
      collapsed: false,
      link: {
        type: 'generated-index',
        title: 'Server',
      },
      items: ['server/basics', 'server/decorator'],
    },
    {
      type: 'category',
      label: 'Client',
      link: {
        type: 'generated-index',
        title: 'Client',
      },
      items: ['client/http'],
    },
    {
      type: 'category',
      label: 'Advanced',
      link: {
        type: 'generated-index',
        title: 'Advanced',
      },
      items: ['advanced/logging'],
    },
    {
      type: 'html',
      value: '<hr>',
      defaultStyle: true, // TODO: Styling
    },
    {
      type: 'html',
      value: 'Tutorials',
      defaultStyle: true, // TODO: Styling
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
      items: ['tutorials/rest/create-a-server'],
    },
    {
      type: 'category',
      label: 'gRPC tutorial',
      link: {
        type: 'doc',
        id: 'tutorials/grpc/index',
      },
      items: ['tutorials/grpc/define-a-service'],
    },
    {
      type: 'category',
      label: 'Thrift tutorial',
      link: {
        type: 'doc',
        id: 'tutorials/thrift/index',
      },
      items: ['tutorials/thrift/define-a-service'],
    },
    {
      type: 'html',
      value: '<hr>',
      defaultStyle: true, // TODO: Styling
    },
    {
      type: 'link',
      label: 'API documentation',
      href: 'https://javadoc.io/doc/com.linecorp.armeria/armeria-javadoc/latest/index.html',
    },
  ],
};

export default sidebars;
