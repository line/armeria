import React, {Dispatch, SetStateAction, useReducer} from 'react';

export interface Row {
  key: string;
  value: string;
}

export enum ACTION {
  REMOVE_ROW,
  CHANGE_CELL,
}

const editorReducer = (state, action) => {
  switch (action.type) {
    case ACTION.REMOVE_ROW: {
      return {};
    }
    case ACTION.CHANGE_CELL: {
      return { ...user, isAdmin: !state.isAdmin };
    }
    default: {
      throw new Error(`unexpected action.type: ${action.type}`);
    }
  }
};

const [user, dispatchUser] = useReducer(userReducer, initialUserState);

const ValueListContext = React.createContext<
  ([Row[], Dispatch<SetStateAction<Row[]>>]) | undefined
>(undefined);

export { ValueListContext };
