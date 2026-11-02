import * as SwitchPrimitive from '@radix-ui/react-switch';
import styled from 'styled-components';
import { interactiveStates } from './buttonStates';

const Track = styled(SwitchPrimitive.Root)`
  display: flex;
  align-items: center;
  justify-content: flex-start;
  width: ${(props) => props.theme.sizes.switchWidth};
  height: ${(props) => props.theme.sizes.switchHeight};
  padding: ${(props) => props.theme.borderWidths.thin};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.control};
  cursor: pointer;

  &[data-state='checked'] {
    justify-content: flex-end;
    background: ${(props) => props.theme.colors.accent};
  }

  ${interactiveStates}
`;

const Thumb = styled(SwitchPrimitive.Thumb)`
  width: ${(props) => props.theme.sizes.switchThumb};
  height: ${(props) => props.theme.sizes.switchThumb};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.surfaceRaised};
`;

export interface SwitchProps {
  checked: boolean;
  onCheckedChange: (checked: boolean) => void;
  // Accessible name; the queue pause toggle shows "Pause the queue".
  label: string;
  disabled?: boolean;
  // Why the switch is disabled, when the reason is not obvious (item 87).
  title?: string;
}

// The queue pause toggle, on Radix's switch: a real `<button role="switch">` with
// aria-checked, the shared focus ring and disabled states, and a flex-positioned thumb
// (so no offsets are computed).
export function Switch({ checked, onCheckedChange, label, disabled, title }: SwitchProps) {
  return (
    <Track
      checked={checked}
      onCheckedChange={onCheckedChange}
      aria-label={label}
      disabled={disabled}
      title={title}
    >
      <Thumb />
    </Track>
  );
}
