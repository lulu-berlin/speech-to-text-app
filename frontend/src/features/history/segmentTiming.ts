import type { Segment } from '../../shared/api/types';

// The way to find which segment the playhead is in: a binary search over the segments'
// start times (spec: "a binary search over the segments' start times on each player
// timeupdate, not a scan"). Segments are ordered by start time.

// Returns the segment whose [startMs, endMs) contains `ms`, or null when `ms` falls in a
// gap or past the transcript's end.
export function segmentAtTime(segments: readonly Segment[], ms: number): Segment | null {
  let low = 0;
  let high = segments.length - 1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    const segment = segments[mid]!;
    if (ms < segment.startMs) {
      high = mid - 1;
    } else if (ms >= segment.endMs) {
      low = mid + 1;
    } else {
      return segment;
    }
  }
  return null;
}
