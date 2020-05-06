import { Typography } from 'antd';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React, { useState } from 'react';
import { Helmet } from 'react-helmet';

interface ShortUrlRedirectProps {
  pageContext: { href: string };
}

const ShortUrlRedirect: React.FC<ShortUrlRedirectProps> = props => {
  const [manualRedirect, setManualRedirect] = useState(false);
  setTimeout(() => setManualRedirect(true), 3000);
  return (
    <>
      <Helmet title="Redirecting">
        <meta
          httpEquiv="refresh"
          content={`0; url=${props.pageContext.href}`}
        />
        <script>{`
        window.location = '${props.pageContext.href}';
        `}</script>
      </Helmet>
      {manualRedirect ? (
        <div
          style={{
            display: 'flex',
            width: '100vw',
            height: '100vh',
            alignItems: 'center',
            justifyContent: 'center',
          }}
        >
          <main>
            <Typography.Paragraph>
              <OutboundLink href={props.pageContext.href}>
                Click here
              </OutboundLink>{' '}
              if not redirected.
            </Typography.Paragraph>
          </main>
        </div>
      ) : (
        ''
      )}
    </>
  );
};

export default ShortUrlRedirect;
