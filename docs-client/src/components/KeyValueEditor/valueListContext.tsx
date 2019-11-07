import React, { Dispatch, SetStateAction } from 'react';

export interface Row {
  key: string;
  value: string;
}
export const CreateDefaultRow = (): Row => {
  return {
    key: '',
    value: '',
  };
};

const ValueListContext = React.createContext<
  ([string, Dispatch<SetStateAction<string>>]) | undefined
>(undefined);

export { ValueListContext };
