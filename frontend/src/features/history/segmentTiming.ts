import type { Segment } from '../../shared/api/types';

// The way to find the current segment (item 52's term): a binary search over the segments'
// start times (spec: "a binary search over the segments' start times on each player
// timeupdate, not a scan"). Segments are ordered by start time.

// Returns the last segment that starts at or before `ms`: the one whose [startMs, endMs)
// contains it, or in a gap the segment just played, until the next one starts (item 52).
// Null before the first segment starts.
export function currentSegmentAt(segments: readonly Segment[], ms: number): Segment | null {
  let low = 0;
  let high = segments.length - 1;
  let found: Segment | null = null;
  while (low <= high) {
    const mid = (low + high) >> 1;
    const segment = segments[mid]!;
    if (ms < segment.startMs) {
      high = mid - 1;
    } else {
      found = segment;
      low = mid + 1;
    }
  }
  return found;
}
