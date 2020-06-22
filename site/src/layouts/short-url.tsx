import React from 'react';

import Redirect from './redirect.tsx';

interface ShortUrlRedirectProps {
  pageContext: { href: string };
}

const ShortUrlRedirect: React.FC<ShortUrlRedirectProps> = (props) => <Redirect href={props.pageContext.href} />;

export default ShortUrlRedirect;
