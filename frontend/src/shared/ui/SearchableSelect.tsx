import { useId, useMemo } from 'react';
import type { KeyboardEvent } from 'react';
import { useMachine } from '@xstate/react';
import styled from 'styled-components';
import { comboboxMachine } from '../state/comboboxMachine';
import { floatingPanel } from './floatingPanel';
import { focusRing } from './focusRing';

export interface SearchableSelectOption {
  value: string;
  label: string;
}

export interface SearchableSelectProps {
  // The id of the input, for a visible label's htmlFor (the aria-label names it too).
  id?: string;
  // The accessible name of the control (also the listbox's).
  label: string;
  options: SearchableSelectOption[];
  value: string;
  onChange: (value: string) => void;
  // Set when the value is rejected: the input is marked invalid and described by the field's
  // error line (the caller owns that line and its id).
  invalid?: boolean;
  describedBy?: string;
  // Set while the field must not change, e.g. before the stored settings are known.
  disabled?: boolean;
  // The field's own focus, for a form that keeps a focused draft (the settings page).
  onFocus?: () => void;
  onBlur?: () => void;
}

// The searchable select (item 14): a combobox input that shows the selected option's
// label, opens a filterable listbox on focus or click, and selects with the mouse or the
// arrow keys + Enter (ADR 0038: the open/filter/active interaction is the combobox
// machine, the option list stays a prop). The value is the option's code; the input
// text is only a label, so typing never invents a value.
const Field = styled.input`
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.md};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.border};
  border-radius: ${(props) => props.theme.radii.button};
  background: ${(props) => props.theme.colors.surface};
  color: ${(props) => props.theme.colors.text};
  font: inherit;

  ${focusRing}
`;

const Listbox = styled.ul`
  position: absolute;
  z-index: 1;
  margin: 0;
  padding: ${(props) => props.theme.spacing.xs};
  list-style: none;
  max-height: ${(props) => props.theme.sizes.listboxMaxHeight};
  overflow-y: auto;
  ${floatingPanel}
`;

const Option = styled.li<{ $active: boolean }>`
  padding: ${(props) => props.theme.spacing.sm} ${(props) => props.theme.spacing.lg};
  border-radius: ${(props) => props.theme.radii.button};
  color: ${(props) => props.theme.colors.text};
  cursor: pointer;
  background: ${(props) => (props.$active ? props.theme.colors.surface : 'transparent')};

  &[aria-selected='true'] {
    font-weight: ${(props) => props.theme.fontWeights.medium};
  }
`;

const Wrap = styled.div`
  position: relative;
`;

export function SearchableSelect({
  id,
  label,
  options,
  value,
  onChange,
  invalid,
  describedBy,
  disabled,
  onFocus,
  onBlur,
}: SearchableSelectProps) {
  const [combobox, sendCombobox] = useMachine(comboboxMachine);
  const listboxId = useId();
  const open = combobox.matches('open');
  const query = combobox.context.query;
  const activeIndex = combobox.context.activeIndex;

  const selected = options.find((option) => option.value === value);
  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (needle === '') {
      return options;
    }
    return options.filter(
      (option) =>
        option.label.toLowerCase().includes(needle) || option.value.toLowerCase().includes(needle),
    );
  }, [options, query]);

  const select = (option: SearchableSelectOption) => {
    onChange(option.value);
    sendCombobox({ type: 'CLOSE' });
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'Enter') {
        event.preventDefault();
        sendCombobox({ type: 'OPEN' });
      }
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        sendCombobox({ type: 'ARROW_DOWN', max: filtered.length });
        break;
      case 'ArrowUp':
        event.preventDefault();
        sendCombobox({ type: 'ARROW_UP', max: filtered.length });
        break;
      case 'Enter': {
        event.preventDefault();
        const active = filtered[activeIndex];
        if (active !== undefined) {
          select(active);
        }
        break;
      }
      case 'Escape':
        event.preventDefault();
        sendCombobox({ type: 'CLOSE' });
        break;
      case 'Tab':
        sendCombobox({ type: 'CLOSE' });
        break;
    }
  };

  const active = filtered[activeIndex];

  return (
    <Wrap>
      <Field
        id={id}
        type="text"
        role="combobox"
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
        disabled={disabled}
        aria-expanded={open}
        aria-controls={listboxId}
        aria-activedescendant={
          open && active !== undefined ? `${listboxId}-${activeIndex}` : undefined
        }
        value={open ? query : (selected?.label ?? '')}
        onChange={(event) => sendCombobox({ type: 'QUERY', value: event.target.value })}
        onFocus={() => {
          onFocus?.();
          sendCombobox({ type: 'OPEN' });
        }}
        onClick={() => sendCombobox({ type: 'OPEN' })}
        onBlur={() => {
          onBlur?.();
          sendCombobox({ type: 'CLOSE' });
        }}
        onKeyDown={onKeyDown}
      />
      {open ? (
        <Listbox id={listboxId} role="listbox" aria-label={label}>
          {filtered.map((option, index) => (
            <Option
              key={option.value}
              id={`${listboxId}-${index}`}
              role="option"
              aria-selected={option.value === value}
              $active={index === activeIndex}
              onMouseDown={(event) => {
                event.preventDefault();
                select(option);
              }}
            >
              {option.label} ({option.value})
            </Option>
          ))}
        </Listbox>
      ) : null}
    </Wrap>
  );
}
