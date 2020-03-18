import { MailOutlined } from '@ant-design/icons';
import { Input, message } from 'antd';
import EmailValidator from 'email-validator';
import jsonp from 'jsonp';
import React from 'react';
import DOMPurify from 'dompurify';

import Emoji from './emoji';

interface MailchimpProps {
  url?: string;
  botCode?: string;
}

const Mailchimp: React.FC<MailchimpProps> = givenProps => {
  let props: MailchimpProps;
  if (!givenProps.url) {
    props = {
      url:
        'https://github.us19.list-manage.com/subscribe/post?u=3447f8227584634e6ee046edf&id=852d70ccdc',
      botCode: 'b_3447f8227584634e6ee046edf_852d70ccdc',
    };
  } else {
    props = givenProps;
  }

  const [email, setEmail] = React.useState('');
  const [sending, setSending] = React.useState(false);
  const messageDuration = 5;

  function doSubmit(
    value: string,
    event:
      | React.SyntheticEvent<HTMLInputElement>
      | React.MouseEvent<HTMLElement>
      | React.KeyboardEvent<HTMLInputElement>,
  ) {
    event.preventDefault();
    if (!EmailValidator.validate(email)) {
      message.warn('Please enter a valid e-mail address.', messageDuration);
      return;
    }

    const url = `${props.url.replace(
      '/post?',
      '/post-json?',
    )}&EMAIL=${encodeURIComponent(email)}&${props.botCode}=`;

    setSending(true);
    jsonp(url, { param: 'c' }, (err: any, data: any) => {
      setSending(false);
      if (err || data.result !== 'success') {
        message.error(
          <span
            // eslint-disable-next-line react/no-danger
            dangerouslySetInnerHTML={{
              __html: DOMPurify.sanitize(
                data.msg || 'Failed to sign up. Please try again later.',
              ),
            }}
          />,
          messageDuration,
        );
      } else {
        setEmail('');
        message.info(
          <Emoji text="Thank you for signing up! 🙇" />,
          messageDuration,
        );
      }
    });
  }

  return (
    <Input.Search
      type="email"
      prefix={<MailOutlined />}
      placeholder="E-mail"
      enterButton="Sign up"
      required
      aria-required
      value={email}
      onChange={e => setEmail(e.target.value)}
      onSearch={doSubmit}
      loading={sending}
    />
  );
};

export default Mailchimp;
