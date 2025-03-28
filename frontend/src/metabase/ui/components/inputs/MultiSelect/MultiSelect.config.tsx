import { MultiSelect, Pill } from "@mantine/core";

import { DefaultSelectItem, selectOverrides } from "../Select";

import S from "./MultiSelect.module.css";

export const multiSelectOverrides = {
  MultiSelect: MultiSelect.extend({
    defaultProps: {
      radius: "sm",
      size: "md",
      variant: "default",
      maxDropdownHeight: 512,
      hidePickedOptions: true,
      renderOption: (props) => (
        <DefaultSelectItem {...props.option} selected={props.checked} />
      ),
      withScrollArea: false,
      comboboxProps: {
        withinPortal: true,
        keepMounted: false,
      },
      clearButtonProps: {
        color: "text-dark",
      },
      "data-testid": "multi-select",
      inputWrapperOrder: ["label", "description", "input", "error"],
    },
    classNames: {
      ...(selectOverrides?.Select?.classNames ?? {}),
      wrapper: S.MultiSelectWrapper,
      input: S.MultiSelectInput,
      inputField: S.MultiSelectInputField,
      pillsList: S.MultiSelectPillsList,
      pill: S.MultiSelectPill,
    },
  }),
  Pill: Pill.extend({
    classNames: {
      root: S.MultiSelectPill,
      remove: S.MultiSelectPillRemove,
    },
  }),
};
