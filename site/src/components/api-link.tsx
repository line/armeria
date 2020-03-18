import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import styles from './api-link.module.less';

interface TypeLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const TypeLink: React.FC<TypeLinkProps> = props => {
  let simpleTypeName = props.name;
  const lastDotIdx = simpleTypeName.lastIndexOf('.');
  if (lastDotIdx >= 0) {
    simpleTypeName = simpleTypeName.substring(lastDotIdx + 1);
  }

  let suffix = '';
  if (props.plural) {
    if (simpleTypeName.match(/(ch|s|sh|x|z)$/)) {
      suffix = 'es';
    } else {
      suffix = 's';
    }
  }

  if (props.href) {
    return (
      <code>
        <OutboundLink href={props.href}>{simpleTypeName}</OutboundLink>
        <span className={styles.typeLinkSuffix}>{suffix}</span>
      </code>
    );
  }

  return (
    <code>
      {simpleTypeName}
      <span className={styles.typeLinkSuffix}>{suffix}</span>
    </code>
  );
};

export { TypeLink };
