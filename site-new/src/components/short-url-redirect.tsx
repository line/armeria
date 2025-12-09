import React, { useEffect } from 'react';
import Redirect from '@site/src/components/redirect';

interface ShortUrlPluginData {
  pluginData: { href: string };
}

const extractDomain = (url: string): string => {
  try {
    return new URL(url).hostname;
  } catch {
    return '';
  }
};

const ShortUrlRedirect: React.FC<ShortUrlPluginData> = ({ pluginData }) => {
  const href = pluginData.href;

  // Send the GA outbound click event manually before redirecting,
  // since JavaScript-based redirects are not tracked automatically.
  // Note that gtag will only be loaded in Production.
  useEffect(() => {
    if (typeof window.gtag === 'function') {
      window.gtag('event', 'click', {
        link_id: '',
        link_classes: '',
        link_url: href,
        link_domain: extractDomain(href),
        outbound: true,
        event_callback: () => {},
      });
    }
  }, [href]);

  return <Redirect href={href} />;
};

export default ShortUrlRedirect;
