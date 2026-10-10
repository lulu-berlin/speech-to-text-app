import { useId, useMemo, useState } from 'react';
import type { KeyboardEvent } from 'react';
import styled from 'styled-components';
import { floatingPanel } from './floatingPanel';
import { focusRing } from './focusRing';

export interface SearchableSelectOption {
  value: string;
  label: string;
}

export interface SearchableSelectProps {
  // The accessible name of the control (also the listbox's).
  label: string;
  options: SearchableSelectOption[];
  value: string;
  onChange: (value: string) => void;
}

// The searchable select (item 14): a combobox input that shows the selected option's
// label, opens a filterable listbox on focus or click, and selects with the mouse or the
// arrow keys + Enter. The value is the option's code; the input text is only a label, so
// typing never invents a value (the listbox filters, selection sets the value).
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

  &[aria-selected='true'] {
    font-weight: ${(props) => props.theme.fontWeights.medium};
  }

  ${(props) => (props.$active ? `background: ${props.theme.colors.surface};` : '')}
`;

const Wrap = styled.div`
  position: relative;
`;

export function SearchableSelect({ label, options, value, onChange }: SearchableSelectProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [activeIndex, setActiveIndex] = useState(0);
  const listboxId = useId();

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

  const openListbox = () => {
    setQuery('');
    setActiveIndex(0);
    setOpen(true);
  };

  const select = (option: SearchableSelectOption) => {
    onChange(option.value);
    setQuery(option.label);
    setOpen(false);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (!open) {
      if (event.key === 'ArrowDown' || event.key === 'Enter') {
        event.preventDefault();
        openListbox();
      }
      return;
    }
    switch (event.key) {
      case 'ArrowDown':
        event.preventDefault();
        setActiveIndex((index) => Math.min(index + 1, filtered.length - 1));
        break;
      case 'ArrowUp':
        event.preventDefault();
        setActiveIndex((index) => Math.max(index - 1, 0));
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
        setOpen(false);
        setQuery(selected?.label ?? '');
        break;
      case 'Tab':
        setOpen(false);
        break;
    }
  };

  const active = filtered[activeIndex];

  return (
    <Wrap>
      <Field
        type="text"
        role="combobox"
        aria-label={label}
        aria-expanded={open}
        aria-controls={listboxId}
        aria-activedescendant={
          open && active !== undefined ? `${listboxId}-${activeIndex}` : undefined
        }
        value={open ? query : (selected?.label ?? '')}
        onChange={(event) => {
          setQuery(event.target.value);
          setActiveIndex(0);
          if (!open) {
            setOpen(true);
          }
        }}
        onFocus={openListbox}
        onClick={openListbox}
        onBlur={() => {
          setOpen(false);
          setQuery(selected?.label ?? '');
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
