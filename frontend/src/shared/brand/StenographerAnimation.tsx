import { styled } from 'styled-components';

// The artwork's style rules are nested under the component's own class (`&`), so the
// generic class names (.word, .head) cannot reach elements outside the component.
const StyledSvg = styled.svg`
  display: block;

  & .wave {
    opacity: 0;
  }
  & .head {
    transform-box: view-box;
    transform-origin: 50% 42%;
  }
  & .word {
    opacity: 0;
  }
`;

// The brand mascot (item 17): a seated stenographer at a steno machine. It is decorative
// wherever it appears, so it carries no role or accessible name of its own.
export function StenographerAnimation() {
  return <StyledSvg viewBox="0 0 400 400" />;
}
