import * as SliderPrimitive from '@radix-ui/react-slider';
import styled from 'styled-components';
import { focusRing } from './focusRing';

const Root = styled(SliderPrimitive.Root)`
  display: flex;
  align-items: center;
  cursor: pointer;
`;

const Track = styled(SliderPrimitive.Track)`
  position: relative;
  flex-grow: 1;
  height: ${(props) => props.theme.spacing.xs};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.control};
`;

const Range = styled(SliderPrimitive.Range)`
  position: absolute;
  height: 100%;
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.accent};
`;

// The thumb carries the slider role (Radix) and is the focusable element, so the focus
// ring and the disabled state live here.
const Thumb = styled(SliderPrimitive.Thumb)`
  display: block;
  width: ${(props) => props.theme.sizes.sliderThumb};
  height: ${(props) => props.theme.sizes.sliderThumb};
  border: ${(props) => props.theme.borderWidths.thin} solid ${(props) => props.theme.colors.control};
  border-radius: ${(props) => props.theme.radii.pill};
  background: ${(props) => props.theme.colors.surfaceRaised};

  ${focusRing}

  &[data-disabled] {
    cursor: default;
  }
`;

export interface SliderProps {
  value: number;
  max: number;
  onChange: (value: number) => void;
  // The value committed when the interaction ends (pointer released, key pressed): a drag's
  // intermediate onChange ticks are drafts, not decisions (item 29's seek bar).
  onCommit?: (value: number) => void;
  // Accessible name (lands on the thumb, which is Radix's slider role); the seek bar in
  // item 15 names the task.
  label: string;
  disabled?: boolean;
}

// The audio seek bar (item 15), on Radix's slider: a single thumb, arrow-key stepping and
// the slider role with the value exposed through aria. Radix places the range and thumb;
// the track and the thumb's looks come from the tokens.
export function Slider({ value, max, onChange, onCommit, label, disabled }: SliderProps) {
  return (
    <Root
      value={[value]}
      max={max}
      step={1}
      disabled={disabled}
      onValueChange={(values) => {
        const [next] = values;
        if (next !== undefined) {
          onChange(next);
        }
      }}
      onValueCommit={(values) => {
        const [next] = values;
        if (next !== undefined) {
          onCommit?.(next);
        }
      }}
    >
      <Track>
        <Range />
      </Track>
      <Thumb aria-label={label} aria-disabled={disabled || undefined} />
    </Root>
  );
}
