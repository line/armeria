import React from 'react';

interface ApiLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const ApiLink: React.FC<ApiLinkProps> = (props) => (
  <code>
    <a href={props.href}>{props.name}</a>
  </code>
);

export default ApiLink;
