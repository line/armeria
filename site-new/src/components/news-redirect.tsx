import Redirect from '@site/src/components/redirect';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';

export default function NewsRedirect() {
  const { latestNewsPath } = useDocusaurusContext().siteConfig.customFields;
  return <Redirect href={String(latestNewsPath)} />;
}
