import React from 'react';
import styles from './api-link.module.css';

interface ApiLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const ApiLink: React.FC<ApiLinkProps> = (props) => {
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

  return (
    <code>
      <a href={href} title={title}>
        {simpleName}
        <span className={styles.suffix}>{suffix}</span>
      </a>
    </code>
  );
};

export default ApiLink;
