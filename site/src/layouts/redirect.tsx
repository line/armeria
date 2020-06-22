import { Typography } from 'antd';
import { OutboundLink } from 'gatsby-plugin-google-analytics';
import React, { useState } from 'react';
import { Helmet } from 'react-helmet';

interface RedirectProps {
  href: string;
}

const Redirect: React.FC<RedirectProps> = (props) => {
  const [manualRedirect, setManualRedirect] = useState(false);
  setTimeout(() => setManualRedirect(true), 3000);
  return (
    <>
      <Helmet title="Redirecting">
        <meta httpEquiv="refresh" content={`0; url=${props.href}`} />
        <script>{`
        window.location = '${props.href}';
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
              <OutboundLink href={props.href}>Click here</OutboundLink> if not
              redirected.
            </Typography.Paragraph>
          </main>
        </div>
      ) : (
        ''
      )}
    </>
  );
};

export default Redirect;
