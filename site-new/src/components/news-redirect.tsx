import React from 'react';
import Redirect from '@site/src/components/redirect';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

const NewsRedirect = () => {
  const { latestNewsPath } = useDocusaurusContext().siteConfig.customFields;
  return <Redirect href={String(latestNewsPath)} />;
};

export default NewsRedirect;
