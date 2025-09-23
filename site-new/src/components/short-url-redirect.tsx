import React from 'react';
import Redirect from '@site/src/components/redirect';

interface ShortUrlPluginData {
  pluginData: { href: string };
}

const ShortUrlRedirect: React.FC<ShortUrlPluginData> = ({ pluginData }) => {
  return <Redirect href={pluginData.href} />;
};

export default ShortUrlRedirect;
