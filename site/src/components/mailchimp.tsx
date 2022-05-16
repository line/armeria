import { MailOutlined } from '@ant-design/icons';
import { Button, Input, message } from 'antd';
import { ButtonType } from 'antd/es/button';
import EmailValidator from 'email-validator';
import jsonp from 'jsonp';
import React, { useState, useCallback } from 'react';
import DOMPurify from 'dompurify';

import Emoji from './emoji';
import * as styles from './mailchimp.module.less';

interface MailchimpProps {
  url?: string;
  botCode?: string;
  buttonType?: ButtonType;
}

const Mailchimp: React.FC<MailchimpProps> = (givenProps) => {
  let props: MailchimpProps;
  if (!givenProps.url) {
    props = {
      url: 'https://github.us19.list-manage.com/subscribe/post?u=3447f8227584634e6ee046edf&id=852d70ccdc',
      botCode: 'b_3447f8227584634e6ee046edf_852d70ccdc',
      buttonType: givenProps.buttonType,
    };
  } else {
    props = givenProps;
  }

  const [email, setEmail] = useState('');
  const [sending, setSending] = useState(false);
  const messageDuration = 5;

  return (
    <Input.Search
      className={styles.signUpForm}
      type="email"
      prefix={<MailOutlined />}
      placeholder="Your e-mail"
      enterButton={
        <Button
          type={props.buttonType || 'default'}
          title="Sign up for our newsletters"
        >
          Subscribe
        </Button>
      }
      required
      aria-required
      value={email}
      loading={sending}
      onChange={useCallback((e) => setEmail(e.target.value), [])}
      onSearch={useCallback(
        (
          value: string,
          event:
            | React.SyntheticEvent<HTMLInputElement>
            | React.MouseEvent<HTMLElement>
            | React.KeyboardEvent<HTMLInputElement>,
        ) => {
          event.preventDefault();
          if (!EmailValidator.validate(email)) {
            message.warn(
              'Please enter a valid e-mail address.',
              messageDuration,
            );
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
        },
        [email, props.botCode, props.url],
      )}
    />
  );
};

export default Mailchimp;
