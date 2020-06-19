import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import styles from './api-link.module.less';

interface TypeLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const TypeLink: React.FC<TypeLinkProps> = (props) => {
  let simpleName = props.name;
  const lastDotIdx = simpleName.lastIndexOf('.');
  if (lastDotIdx >= 0) {
    simpleName = simpleName.substring(lastDotIdx + 1);
  }

  let suffix = '';
  if (props.plural) {
    if (simpleName.match(/(ch|s|sh|x|z)$/)) {
      suffix = 'es';
    } else {
      suffix = 's';
    }
  }

  let title = '';
  if (simpleName.indexOf('#') > 0) {
    const replaced = simpleName.replace('#', '.');
    title = replaced;
    simpleName = replaced.replace(/ *\([^)]*\)*/, '()');
  } else {
    title = simpleName;
  }

  const simpleTypeNameWithHref = props.href ? (
    <OutboundLink href={props.href} title={title}>
      {simpleName}
    </OutboundLink>
  ) : (
    simpleName
  );

  return (
    <code>
      {simpleTypeNameWithHref}
      <span className={styles.typeLinkSuffix}>{suffix}</span>
    </code>
  );
};

export { TypeLink };
