import React, { type ReactNode } from 'react';
import A from '@theme-original/MDXComponents/A';
import type { Props as AProps } from '@theme/MDXComponents/A';
import ApiLink from '@site/src/components/api-link';

const AWrapper: React.FC = (props: AProps): ReactNode => {
  const href: string = `${props.href || ''}`;
  if (href.startsWith('type://') || href.startsWith('typeplural://')) {
    const prefixLength = href.indexOf('://') + 3;
    const actualHref = href.substring(prefixLength);
    return (
      <ApiLink
        name={props.children.toString()}
        href={actualHref}
        plural={href.startsWith('typeplural://')}
      />
    );
  }

  return (
    // eslint-disable-next-line react/jsx-no-useless-fragment
    <>
      <A {...props} />
    </>
  );
};

export default AWrapper;
