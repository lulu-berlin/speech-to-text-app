import { styled, useTheme } from 'styled-components';

// The artwork's style rules are nested under the component's own class (`&`), so the
// generic class names (.word, .head) cannot reach elements outside the component.
const STENO_SIZE_TOKEN = { header: 'stenoHeader', empty: 'stenoEmpty' } as const;

export type StenographerSize = keyof typeof STENO_SIZE_TOKEN;

const StyledSvg = styled.svg<{ $size: StenographerSize }>`
  display: block;
  width: ${(props) => props.theme.sizes[STENO_SIZE_TOKEN[props.$size]]};
  height: ${(props) => props.theme.sizes[STENO_SIZE_TOKEN[props.$size]]};

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

export interface StenographerAnimationProps {
  size?: StenographerSize;
}

// The brand mascot (item 17): a seated stenographer at a steno machine on a warm backdrop.
// Inlined from docs/oracle of item 17's artwork (stenographer.svg): every color comes from
// the theme's illustration tokens, so the piece follows light and dark mode. It is
// decorative wherever it appears, so it carries no role or accessible name of its own.
export function StenographerAnimation({ size = 'header' }: StenographerAnimationProps) {
  const theme = useTheme();
  const i = theme.illustration;
  return (
    <StyledSvg viewBox="0 0 400 400" $size={size}>
      <defs>
        <radialGradient id="bg" cx="50%" cy="40%" r="60%">
          <stop offset="0%" stopColor={i.backdropFrom} />
          <stop offset="100%" stopColor={i.backdropTo} />
        </radialGradient>
        <linearGradient id="machineFront" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={i.machineFrom} />
          <stop offset="100%" stopColor={i.machineTo} />
        </linearGradient>
        <linearGradient id="desk" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={i.desk} />
          <stop offset="100%" stopColor={i.deskDeep} />
        </linearGradient>
        <linearGradient id="glow" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={i.lampGlow} stopOpacity={0.75} />
          <stop offset="100%" stopColor={i.lampGlow} stopOpacity={0} />
        </linearGradient>
        <clipPath id="circle">
          <circle cx="200" cy="200" r="192" />
        </clipPath>
      </defs>

      {/* Background */}
      <circle cx="200" cy="200" r="192" fill="url(#bg)" />
      <g>
        <circle className="spark" cx="342" cy="78" r="6" fill={i.accentPurpleSoft} />
        <circle className="spark" cx="60" cy="200" r="5" fill={i.accentYellow} />
        <circle className="spark" cx="88" cy="70" r="4" fill={i.machineAccent} />
      </g>

      {/* Sound waves arriving at the headphones */}
      <g fill="none" strokeLinecap="round" strokeWidth={4}>
        <path className="wave" d="M140 118 Q132 132 140 146" stroke={i.accentPurple} />
        <path className="wave" d="M128 108 Q116 132 128 156" stroke={i.accentPink} />
        <path className="wave" d="M116 98 Q100 132 116 166" stroke={i.accentYellow} />
      </g>

      {/* Chair */}
      <rect x="128" y="182" width="144" height="140" rx="30" fill={i.chairBack} />
      <rect x="140" y="194" width="120" height="120" rx="22" fill={i.chairSeat} />

      {/* Torso */}
      <path
        d="M150 218 Q150 186 200 183 Q250 186 250 218 L258 322 L142 322 Z"
        fill={i.accentTeal}
      />
      <path d="M186 184 L200 204 L214 184 Z" fill={i.accentTealDeep} />

      {/* Neck and head */}
      <rect x="190" y="158" width="20" height="30" rx="6" fill={i.skinShade} />
      <g className="head">
        {/* The ear-length hair mop: reads neither short-cropped nor long */}
        <g fill={i.ink}>
          <path d="M162 128 Q158 76 200 74 Q242 76 238 128 Z" />
          <circle cx="163.3" cy="101.6" r="11" />
          <circle cx="167.1" cy="92" r="11" />
          <circle cx="173.1" cy="83.7" r="11" />
          <circle cx="181" cy="77.4" r="11" />
          <circle cx="190.2" cy="73.4" r="11" />
          <circle cx="200" cy="72" r="11" />
          <circle cx="209.8" cy="73.4" r="11" />
          <circle cx="219" cy="77.4" r="11" />
          <circle cx="226.9" cy="83.7" r="11" />
          <circle cx="232.9" cy="92" r="11" />
          <circle cx="236.7" cy="101.6" r="11" />
        </g>
        <circle cx="200" cy="130" r="34" fill={i.skin} />
        <path
          d="M166 130 Q163 94 200 90 Q237 94 234 130 Q230 113 217 113 Q209 105 198 111 Q187 106 179 115 Q171 118 166 130 Z"
          fill={i.ink}
        />
        <path
          d="M186 74 Q194 70 202 72"
          fill="none"
          stroke={i.inkSoft}
          strokeWidth={2.5}
          strokeLinecap="round"
        />
        <ellipse className="eye" cx="188" cy="134" rx="3.5" ry="4.5" fill={i.ink} />
        <ellipse className="eye" cx="212" cy="134" rx="3.5" ry="4.5" fill={i.ink} />
        <circle cx="180" cy="146" r="4" fill={i.cheek} opacity={0.25} />
        <circle cx="220" cy="146" r="4" fill={i.cheek} opacity={0.25} />
        <path
          d="M192 149 Q200 156 208 149"
          fill="none"
          stroke={i.ink}
          strokeWidth={2.5}
          strokeLinecap="round"
        />
        <path
          d="M163 130 Q163 82 200 82 Q237 82 237 130"
          fill="none"
          stroke={i.accentYellow}
          strokeWidth={6}
          strokeLinecap="round"
        />
        <rect x="156" y="119" width="14" height="26" rx="7" fill={i.accentPink} />
        <rect x="230" y="119" width="14" height="26" rx="7" fill={i.accentPink} />
      </g>

      {/* Transcribed words rising from the machine */}
      <g>
        <rect
          className="word"
          x="226"
          y="226"
          width="18"
          height="7"
          rx="3.5"
          fill={i.accentPurple}
        />
        <rect className="word" x="228" y="226" width="12" height="7" rx="3.5" fill={i.accentPink} />
        <rect className="word" x="224" y="226" width="22" height="7" rx="3.5" fill={i.accentTeal} />
        <rect
          className="word"
          x="229"
          y="226"
          width="10"
          height="7"
          rx="3.5"
          fill={i.accentYellow}
        />
        <rect className="word" x="226" y="226" width="16" height="7" rx="3.5" fill={i.accentBlue} />
      </g>

      {/* Lamp light */}
      <path
        className="lamp-glow"
        d="M282 214 L300 226 L312 290 L246 290 Z"
        fill="url(#glow)"
        clipPath="url(#circle)"
      />

      {/* Desk */}
      <g clipPath="url(#circle)">
        <rect x="0" y="290" width="400" height="120" fill="url(#desk)" />
        <rect x="0" y="284" width="400" height="9" rx="2" fill={i.deskRim} />
      </g>

      {/* Desk lamp */}
      <ellipse cx="320" cy="289" rx="20" ry="5" fill={i.machineMetal} />
      <ellipse cx="320" cy="287" rx="16" ry="3.5" fill={i.machineMetalSoft} />
      <path
        d="M320 286 L320 222 Q320 206 304 203"
        fill="none"
        stroke={i.machineMetalSoft}
        strokeWidth={4}
        strokeLinecap="round"
      />
      <circle cx="304" cy="203" r="4" fill={i.machineMetal} />
      <path
        d="M298 194 L310 202 L300 226 L280 213 Z"
        fill={i.accentYellow}
        stroke={i.accentYellow}
        strokeWidth={4}
        strokeLinejoin="round"
      />
      <path d="M282 214 L300 226" stroke={i.lampHot} strokeWidth={3} strokeLinecap="round" />

      {/* Steno machine on a short stand */}
      <ellipse cx="200" cy="288" rx="24" ry="4.5" fill={i.ink} opacity={0.15} />
      <rect x="195" y="268" width="10" height="19" rx="2" fill={i.machineMetal} />
      <ellipse cx="200" cy="287" rx="16" ry="3.5" fill={i.machineMetalSoft} />
      <path d="M154 248 L246 248 L243 270 L157 270 Z" fill="url(#machineFront)" />
      <rect x="166" y="257" width="68" height="3" rx="1.5" fill={i.machineAccent} opacity={0.8} />
      <path d="M161 230 L239 230 L246 248 L154 248 Z" fill={i.machineTop} />
      <g className="keys">
        {[
          [164, 234, 7, 4],
          [173, 234, 7, 4],
          [182, 234, 7, 4],
          [191, 234, 7, 4],
          [202, 234, 7, 4],
          [211, 234, 7, 4],
          [220, 234, 7, 4],
          [229, 234, 7, 4],
          [161, 241, 8, 4],
          [171, 241, 8, 4],
          [181, 241, 8, 4],
          [191, 241, 8, 4],
          [201, 241, 8, 4],
          [211, 241, 8, 4],
          [221, 241, 8, 4],
          [231, 241, 8, 4],
        ].map(([x, y, width, height]) => (
          <rect key={`${x}-${y}`} x={x} y={y} width={width} height={height} rx="1.2" />
        ))}
      </g>

      {/* Left arm: elbow out, hand draped over the machine */}
      <g className="arm-l">
        <path
          d="M160 206 Q136 228 150 243 Q164 248 176 228"
          fill="none"
          stroke={i.accentTealDeep}
          strokeWidth={15}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <g transform="rotate(9 173.8 229)">
          <g className="f f1">
            <rect x="172" y="229.6" width="5" height="10.5" rx="2.5" fill={i.skinShade} />
            <rect x="171.3" y="229" width="5" height="10.5" rx="2.5" fill={i.skin} />
            <ellipse cx="173.8" cy="236.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(3 179.4 229)">
          <g className="f f2">
            <rect x="177.3" y="229.6" width="5.6" height="13.5" rx="2.8" fill={i.skinShade} />
            <rect x="176.6" y="229" width="5.6" height="13.5" rx="2.8" fill={i.skin} />
            <ellipse cx="179.4" cy="239.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g>
          <g className="f f3">
            <rect x="182.8" y="229.6" width="5.8" height="14.5" rx="2.9" fill={i.skinShade} />
            <rect x="182.1" y="229" width="5.8" height="14.5" rx="2.9" fill={i.skin} />
            <ellipse cx="185" cy="240.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(-3 190.6 229)">
          <g className="f f4">
            <rect x="188.5" y="229.6" width="5.6" height="13" rx="2.8" fill={i.skinShade} />
            <rect x="187.8" y="229" width="5.6" height="13" rx="2.8" fill={i.skin} />
            <ellipse cx="190.6" cy="238.8" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(-16 191 228)">
          <g className="thumb">
            <rect x="188.7" y="228.6" width="6" height="10" rx="3" fill={i.skinShade} />
            <rect x="188" y="228" width="6" height="10" rx="3" fill={i.skin} />
            <path
              d="M193.6 233 Q194.4 235.4 193.4 237.2"
              fill="none"
              stroke={i.skinHighlight}
              strokeWidth={1.4}
              strokeLinecap="round"
            />
          </g>
        </g>
        <path
          d="M169 224 Q168 232 171 234 L193 234 Q196 232 195 224 Q182 216 169 224 Z"
          fill={i.skin}
        />
        <ellipse cx="180" cy="226" rx="7" ry="3" fill={i.skinHighlight} opacity={0.6} />
      </g>

      {/* Right arm */}
      <g className="arm-r">
        <path
          d="M240 206 Q264 228 250 243 Q236 248 224 228"
          fill="none"
          stroke={i.accentTealDeep}
          strokeWidth={15}
          strokeLinecap="round"
          strokeLinejoin="round"
        />
        <g transform="rotate(-9 226.2 229)">
          <g className="f f1">
            <rect x="224.4" y="229.6" width="5" height="10.5" rx="2.5" fill={i.skinShade} />
            <rect x="223.7" y="229" width="5" height="10.5" rx="2.5" fill={i.skin} />
            <ellipse cx="226.2" cy="236.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(-3 220.6 229)">
          <g className="f f2">
            <rect x="218.5" y="229.6" width="5.6" height="13.5" rx="2.8" fill={i.skinShade} />
            <rect x="217.8" y="229" width="5.6" height="13.5" rx="2.8" fill={i.skin} />
            <ellipse cx="220.6" cy="239.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g>
          <g className="f f3">
            <rect x="212.8" y="229.6" width="5.8" height="14.5" rx="2.9" fill={i.skinShade} />
            <rect x="212.1" y="229" width="5.8" height="14.5" rx="2.9" fill={i.skin} />
            <ellipse cx="215" cy="240.3" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(3 209.4 229)">
          <g className="f f4">
            <rect x="207.3" y="229.6" width="5.6" height="13" rx="2.8" fill={i.skinShade} />
            <rect x="206.6" y="229" width="5.6" height="13" rx="2.8" fill={i.skin} />
            <ellipse cx="209.4" cy="238.8" rx="1.7" ry="2.1" fill={i.skinHighlight} />
          </g>
        </g>
        <g transform="rotate(16 209 228)">
          <g className="thumb">
            <rect x="206.7" y="228.6" width="6" height="10" rx="3" fill={i.skinShade} />
            <rect x="206" y="228" width="6" height="10" rx="3" fill={i.skin} />
            <path
              d="M206.4 233 Q205.6 235.4 206.6 237.2"
              fill="none"
              stroke={i.skinHighlight}
              strokeWidth={1.4}
              strokeLinecap="round"
            />
          </g>
        </g>
        <path
          d="M205 224 Q204 232 207 234 L229 234 Q232 232 231 224 Q218 216 205 224 Z"
          fill={i.skin}
        />
        <ellipse cx="220" cy="226" rx="7" ry="3" fill={i.skinHighlight} opacity={0.6} />
      </g>
    </StyledSvg>
  );
}
