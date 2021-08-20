import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React from 'react';

import styles from './api-link.module.less';

interface TypeLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const TypeLink: React.FC<TypeLinkProps> = (props) => {
  // Decode escaped strings such as &lt;init&gt; that represents a constructor.
  let simpleName = decodeURIComponent(props.name);
  const lastDotIdx = simpleName.lastIndexOf('.');
  if (lastDotIdx >= 0) {
    simpleName = simpleName.substring(lastDotIdx + 1);
  }

  let showParams = false;
  let href = props.href;
  if (href) {
    const optionIndex = href.lastIndexOf('?');
    if (optionIndex > 0) {
      showParams = href.substring(optionIndex + 1) === 'full';
      href = href.substring(0, optionIndex);
    }
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
    if (showParams) {
      simpleName = replaced;
    } else {
      simpleName = replaced.replace(/ *\([^)]*\)*/, '()');
    }
  } else {
    title = simpleName;
  }

  const simpleTypeNameWithHref = href ? (
    <OutboundLink href={href} title={title}>
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
