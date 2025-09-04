import React, { useState } from 'react';
import Link from '@docusaurus/Link';
import Head from '@docusaurus/Head';

interface RedirectProps {
  href: string;
}

const Redirect: React.FC<RedirectProps> = (props) => {
  const [manualRedirect, setManualRedirect] = useState(false);
  setTimeout(() => setManualRedirect(true), 3000);
  return (
    <>
      <Head>
        <title>Redirecting</title>
        <meta httpEquiv="refresh" content={`0; url=${props.href}`} />
        <script>{`
        window.location = '${props.href}';
        `}</script>
      </Head>
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
            <p>
              <Link to={props.href}>Click here</Link> if not redirected.
            </p>
          </main>
        </div>
      ) : (
        ''
      )}
    </>
  );
};

export default Redirect;
