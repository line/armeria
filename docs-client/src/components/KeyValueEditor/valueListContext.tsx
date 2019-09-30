import React, { Dispatch, SetStateAction } from 'react';

export interface Row {
  index: number;
  key: string;
  value: string;
}
const ValueListContext = React.createContext<
  ([Row[], Dispatch<SetStateAction<Row[]>>]) | undefined
>(undefined);

export { ValueListContext };
