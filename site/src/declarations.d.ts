declare const graphql: (query: TemplateStringsArray) => void;
declare module '*.less' {
  const content: { [className: string]: string };
  export default content;
}
declare module '*.png';
declare module 'react-mailchimp-form';
declare module 'react-reveal/Bounce';

declare module '@mdx-js/react' {
  import React from 'react';

  type ComponentType =
    | 'a'
    | 'blockquote'
    | 'code'
    | 'delete'
    | 'em'
    | 'h1'
    | 'h2'
    | 'h3'
    | 'h4'
    | 'h5'
    | 'h6'
    | 'hr'
    | 'img'
    | 'inlineCode'
    | 'li'
    | 'ol'
    | 'p'
    | 'pre'
    | 'strong'
    | 'sup'
    | 'table'
    | 'td'
    | 'thematicBreak'
    | 'tr'
    | 'ul';
  export type Components = {
    [key in ComponentType]?: React.ComponentType<{ children: any }>;
  };
  export interface MDXProviderProps {
    children: React.ReactNode;
    components: Components;
  }
  // eslint-disable-next-line react/prefer-stateless-function
  export class MDXProvider extends React.Component<MDXProviderProps> {}
}
