import React from 'react';
import styles from './api-link.module.less';

interface ApiLinkProps {
  name: string;
  href?: string;
  plural?: boolean;
}

const ApiLink: React.FC<ApiLinkProps> = (props) => {
  const simpleName = props.name;

  let suffix = '';
  if (props.plural) {
    if (simpleName.match(/(ch|s|sh|x|z)$/)) {
      suffix = 'es';
    } else {
      suffix = 's';
    }
  }

  return (
    <code>
      <a href={props.href}>
        {props.name}
        <span className={styles.suffix}>{suffix}</span>
      </a>
    </code>
  );
};

export default ApiLink;
