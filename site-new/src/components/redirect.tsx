import React, { useState, useEffect} from 'react';
import Link from '@docusaurus/Link';
import Head from '@docusaurus/Head';
import { Redirect as DocusaurusRedirect } from '@docusaurus/router';

interface RedirectProps {
  href: string;
}

const isValidUrl = (url: string): boolean => {
  if (!url) return false;

  return (
    url.startsWith('http://') ||
    url.startsWith('https://') ||
    url.startsWith('/')
  );
};

const Redirect: React.FC<RedirectProps> = (props) => {
  const [manualRedirect, setManualRedirect] = useState(false);

  if (!isValidUrl(props.href)) {
    console.error('Invalid redirect URL:', props.href);
    return <DocusaurusRedirect to="/404" />;
  }

  useEffect(() => {
    const timer = setTimeout(() => setManualRedirect(true), 3000);
    return () => clearTimeout(timer);
  }, []);

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
