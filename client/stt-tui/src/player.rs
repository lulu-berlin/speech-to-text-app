//! A transcript's playback (roadmap item 67): the service's FLAC clip decoded here and written to
//! the default output, with the playhead the screen reads. The audio thread owns the output and the
//! clip; the UI only sends it commands and asks where it is. `STT_AUDIO_SINK=null` plays silently in
//! real time instead of opening a device - the journey's silent output, and how the pty tests run
//! without a sound card; `STT_AUDIO_SINK=frozen` silences it too and leaves the playhead where a
//! seek put it, so a test of seeks and marks reads no clock.
//!
//! The output streams the device's own default layout - its rate and channel count - and the clip
//! is mapped onto it: a mono clip fills every device channel and a rate mismatch is interpolated
//! linearly. Writing the clip's layout into a device that expects another plays it at the wrong
//! speed (a mono clip on a stereo device runs twice as fast).

use std::io;
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{FromSample, SampleFormat, SizedSample, StreamConfig};

/// What the UI asks the player to do.
enum Command {
    /// Play `bytes` (the service's FLAC answer) from `from` - up to `until` and back to `from`
    /// when given, as a segment played alone.
    Play {
        bytes: Vec<u8>,
        from: Duration,
        until: Option<Duration>,
    },
    /// Play the loaded clip from `from` up to `until`, then stand back at `from`.
    PlayWindow {
        from: Duration,
        until: Duration,
    },
    Pause,
    /// Continue what was paused, from where it stopped.
    Resume,
    /// Move the playhead to `to`; a playing clip goes on from there.
    Seek(Duration),
}

/// The output's layout: what the device's default configuration streams.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct Device {
    channels: usize,
    rate: u32,
}

/// The clip the sink draws from, and where it has got to; the output callback and the UI share it.
struct Shared {
    /// The clip's samples, interleaved by `channels`, at `rate`.
    clip: Vec<f32>,
    channels: usize,
    rate: u32,
    /// How far the sink has got, in the clip's frames - fractional between two of them when the
    /// device runs at another rate.
    at: f64,
    /// Whether the sink takes frames at all: paused or stopped, it writes silence.
    playing: bool,
    /// A segment played alone (item 52's isolated play): its start and end, in the clip's frames.
    /// At the end playback stops back at the start; a seek ends it.
    window: Option<(f64, f64)>,
    /// The service or the output failed: what the bar says instead of playing (item 67's edge).
    failed: Option<String>,
    /// Plays asked for that the audio thread has not handled yet: a clip being decoded, an output
    /// being opened. The screen keeps redrawing while any is pending, so the bar never waits on
    /// an unrelated event to show the play starting - or failing.
    pending: u32,
}

impl Shared {
    fn silent() -> Shared {
        Shared {
            clip: Vec::new(),
            channels: 1,
            rate: 48_000,
            at: 0.0,
            playing: false,
            window: None,
            failed: None,
            pending: 0,
        }
    }

    /// The clip's length in frames: the playhead's end.
    fn frames(&self) -> f64 {
        (self.clip.len() / self.channels.max(1)) as f64
    }

    /// The clip's frames in `from`.
    fn at(&self, from: Duration) -> f64 {
        (from.as_secs_f64() * f64::from(self.rate)).min(self.frames())
    }

    /// Moves the head on to `at` as it plays: the clip's end stops it there, a window's end
    /// stops it back at the window's start.
    fn advance(&mut self, at: f64) {
        if let Some((start, end)) = self.window
            && at >= end
        {
            self.at = start;
            self.playing = false;
            self.window = None;
            return;
        }
        self.at = at.min(self.frames());
        if self.at >= self.frames() {
            self.playing = false;
        }
    }

    /// Starts playing at `from`, alone up to `until` when given.
    fn start(&mut self, from: Duration, until: Option<Duration>) -> f64 {
        let at = self.at(from);
        self.at = at;
        self.window = until.map(|until| (at, self.at(until)));
        self.playing = true;
        at
    }
}

/// The handle the screen keeps: commands in, the playhead out.
pub struct Player {
    commands: Sender<Command>,
    shared: Arc<Mutex<Shared>>,
}

impl Player {
    /// Starts the player: a thread that owns the audio output for as long as the TUI runs.
    pub fn start() -> Player {
        let (commands, receiver) = mpsc::channel::<Command>();
        let shared = Arc::new(Mutex::new(Shared::silent()));
        let sink = shared.clone();
        thread::Builder::new()
            .name("stt-player".to_string())
            .spawn(move || run(receiver, sink))
            .expect("the player thread starts");
        Player { commands, shared }
    }

    /// Plays `bytes` from `from` - alone up to `until` when given; a decode or output failure lands
    /// in [`Player::failed`].
    pub fn play(&self, bytes: Vec<u8>, from: Duration, until: Option<Duration>) {
        self.request(Command::Play { bytes, from, until });
    }

    /// Sends a play - a clip, a window or a resume - counted as pending until the audio thread has
    /// handled it.
    fn request(&self, command: Command) {
        self.shared.lock().expect("the player's state").pending += 1;
        let _ = self.commands.send(command);
    }

    /// Plays the loaded clip from `from` up to `until` alone, then stands back at `from`. The UI
    /// reads the new playhead and window at once, before the audio thread takes the command.
    pub fn play_window(&self, from: Duration, until: Duration) {
        {
            let mut state = self.shared.lock().expect("the player's state");
            if !state.clip.is_empty() {
                state.start(from, Some(until));
            }
        }
        self.request(Command::PlayWindow { from, until });
    }

    /// The segment playing alone, as its start and end, while one does.
    pub fn window(&self) -> Option<(Duration, Duration)> {
        let state = self.shared.lock().expect("the player's state");
        let rate = f64::from(state.rate.max(1));
        state.window.map(|(start, end)| {
            (
                Duration::from_secs_f64(start / rate),
                Duration::from_secs_f64(end / rate),
            )
        })
    }

    pub fn pause(&self) {
        let _ = self.commands.send(Command::Pause);
    }

    /// Continues what was paused, from where it stopped.
    pub fn resume(&self) {
        self.request(Command::Resume);
    }

    /// Moves the playhead to `to`, clamped to the clip; a playing clip goes on from there, no
    /// longer alone. The UI reads the new playhead at once, before the audio thread takes the
    /// command.
    pub fn seek(&self, to: Duration) {
        {
            let mut state = self.shared.lock().expect("the player's state");
            if !state.clip.is_empty() {
                state.at = state.at(to);
                state.window = None;
            }
        }
        let _ = self.commands.send(Command::Seek(to));
    }

    /// Whether the sink is taking frames.
    pub fn playing(&self) -> bool {
        self.shared.lock().expect("the player's state").playing
    }

    /// Whether the screen should keep redrawing: a clip plays, or a play is on its way.
    pub fn busy(&self) -> bool {
        let state = self.shared.lock().expect("the player's state");
        state.playing || state.pending > 0
    }

    /// The playhead, as the sink has taken frames.
    pub fn position(&self) -> Duration {
        let shared = self.shared.lock().expect("the player's state");
        Duration::from_secs_f64(shared.at / f64::from(shared.rate.max(1)))
    }

    /// The clip's length, once one is loaded.
    pub fn length(&self) -> Duration {
        let shared = self.shared.lock().expect("the player's state");
        Duration::from_secs_f64(shared.frames() / f64::from(shared.rate.max(1)))
    }

    /// What went wrong, if the clip could not be decoded or the output opened.
    pub fn failed(&self) -> Option<String> {
        self.shared
            .lock()
            .expect("the player's state")
            .failed
            .clone()
    }
}

/// Where the audio goes, from `STT_AUDIO_SINK`: the sound card (the default), the silent sink that
/// walks the playhead in real time (`null`), the frozen one that only a seek moves (`frozen`), or
/// no device at all (`absent`) - what a machine without a sound card has, for the tests.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Sink {
    Device,
    Null,
    Frozen,
    Absent,
}

impl Sink {
    fn from_env() -> Sink {
        match std::env::var("STT_AUDIO_SINK").as_deref() {
            Ok("null") => Sink::Null,
            Ok("frozen") => Sink::Frozen,
            Ok("absent") => Sink::Absent,
            _ => Sink::Device,
        }
    }
}

/// What the bar says when there is nothing to play on: no output device, or no audio host.
const NO_DEVICE: &str = "no audio device";

/// Opens the output on the first play - and again on a later one while none is open, so a device
/// plugged in after a failure is found. `false`, with the failure stored, when there is none.
fn ensure_output(
    stream: &mut Option<cpal::Stream>,
    sink: Sink,
    shared: &Arc<Mutex<Shared>>,
) -> bool {
    if stream.is_none() {
        let opened = match sink {
            Sink::Absent => Err(io::Error::other(NO_DEVICE)),
            _ => open(shared.clone()),
        };
        match opened {
            Ok(opened) => *stream = Some(opened),
            Err(error) => {
                let mut state = shared.lock().expect("the player's state");
                state.playing = false;
                state.failed = Some(error.to_string());
                return false;
            }
        }
    }
    shared.lock().expect("the player's state").failed = None;
    if let Some(stream) = stream {
        let _ = stream.play();
    }
    true
}

/// The audio thread: the commands, the decoded clip, and the output - a device, or the silent sink
/// (`STT_AUDIO_SINK=null`) that walks the playhead in real time instead.
fn run(receiver: Receiver<Command>, shared: Arc<Mutex<Shared>>) {
    let sink = Sink::from_env();
    // A silent sink takes commands on a timer; the device's output waits for them.
    let silent = matches!(sink, Sink::Null | Sink::Frozen);
    // The stream stays alive for the thread's life; the silent sink has none.
    let mut stream: Option<cpal::Stream> = None;
    let mut clock: Option<(Instant, f64)> = None;
    loop {
        let command = if silent {
            match receiver.recv_timeout(Duration::from_millis(10)) {
                Ok(command) => Some(command),
                Err(RecvTimeoutError::Timeout) => None,
                Err(RecvTimeoutError::Disconnected) => return,
            }
        } else {
            match receiver.recv() {
                Ok(command) => Some(command),
                Err(_) => return,
            }
        };
        let Some(command) = command else {
            // The silent sink's tick: the head follows the wall clock, as a device's does - unless
            // the sink is frozen, when only a seek moves it.
            if let Some((started, frame)) = clock {
                let mut state = shared.lock().expect("the player's state");
                if state.playing && sink != Sink::Frozen {
                    let elapsed = started.elapsed().as_secs_f64();
                    let rate = f64::from(state.rate);
                    state.advance(frame + elapsed * rate);
                }
            }
            continue;
        };
        let request = matches!(
            command,
            Command::Play { .. } | Command::PlayWindow { .. } | Command::Resume
        );
        'handled: {
            match command {
                Command::Play { bytes, from, until } => {
                    let (clip, channels, rate) = match decode(&bytes) {
                        Ok(clip) => clip,
                        Err(error) => {
                            let mut state = shared.lock().expect("the player's state");
                            state.playing = false;
                            state.failed = Some(error.to_string());
                            break 'handled;
                        }
                    };
                    let at = {
                        let mut state = shared.lock().expect("the player's state");
                        state.clip = clip;
                        state.channels = channels;
                        state.rate = rate;
                        state.failed = None;
                        state.start(from, until)
                    };
                    clock = Some((Instant::now(), at));
                    if !silent {
                        ensure_output(&mut stream, sink, &shared);
                    }
                }
                Command::PlayWindow { from, until } => {
                    let at = {
                        let mut state = shared.lock().expect("the player's state");
                        if state.clip.is_empty() {
                            break 'handled;
                        }
                        state.start(from, Some(until))
                    };
                    clock = Some((Instant::now(), at));
                    if !silent {
                        ensure_output(&mut stream, sink, &shared);
                    }
                }
                Command::Pause => {
                    let mut state = shared.lock().expect("the player's state");
                    state.playing = false;
                }
                Command::Resume => {
                    let at = {
                        let mut state = shared.lock().expect("the player's state");
                        if state.clip.is_empty() || state.at >= state.frames() {
                            break 'handled;
                        }
                        state.playing = true;
                        state.at
                    };
                    clock = Some((Instant::now(), at));
                    if !silent {
                        ensure_output(&mut stream, sink, &shared);
                    }
                }
                Command::Seek(to) => {
                    let at = {
                        let mut state = shared.lock().expect("the player's state");
                        if state.clip.is_empty() {
                            break 'handled;
                        }
                        let at = state.at(to);
                        state.at = at;
                        state.window = None;
                        at
                    };
                    clock = Some((Instant::now(), at));
                }
            }
        }
        // The play is handled - started, or failed with the reason stored - and no longer pending.
        if request {
            let mut state = shared.lock().expect("the player's state");
            state.pending = state.pending.saturating_sub(1);
        }
    }
}

/// Decodes a FLAC clip into interleaved `f32` samples: the samples, their channel count and rate.
fn decode(bytes: &[u8]) -> io::Result<(Vec<f32>, usize, u32)> {
    let mut reader = claxon::FlacReader::new(bytes).map_err(io::Error::other)?;
    let info = reader.streaminfo();
    let channels = (info.channels as usize).max(1);
    let rate = info.sample_rate.max(1);
    let scale = 1.0 / (1u64 << (info.bits_per_sample.saturating_sub(1))) as f32;
    let mut clip = Vec::new();
    for sample in reader.samples() {
        let sample = sample.map_err(io::Error::other)?;
        clip.push(sample as f32 * scale);
    }
    Ok((clip, channels, rate))
}

/// Opens the default output in the device's own default layout (its rate and channels) and plays
/// the clip onto it, interpolating when the rates differ.
fn open(shared: Arc<Mutex<Shared>>) -> io::Result<cpal::Stream> {
    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or_else(|| io::Error::other(NO_DEVICE))?;
    let supported = device.default_output_config().map_err(output_error)?;
    let sample_format = supported.sample_format();
    let config: StreamConfig = supported.into();
    let layout = Device {
        channels: usize::from(config.channels).max(1),
        rate: config.sample_rate.max(1),
    };
    let stream = match sample_format {
        SampleFormat::F32 => stream::<f32>(&device, config, shared, layout),
        SampleFormat::I16 => stream::<i16>(&device, config, shared, layout),
        SampleFormat::U16 => stream::<u16>(&device, config, shared, layout),
        other => {
            return Err(io::Error::other(format!(
                "the output does not take {other:?} samples"
            )));
        }
    };
    stream.map_err(output_error)
}

/// An output error as the bar says it: a device or host that is not there is "no audio device",
/// whichever way the system reports it (a Linux machine without a sound card still lists ALSA's
/// default device, and fails on opening it); anything else in cpal's words.
fn output_error(error: cpal::Error) -> io::Error {
    match error.kind() {
        cpal::ErrorKind::DeviceNotAvailable | cpal::ErrorKind::HostUnavailable => {
            io::Error::other(NO_DEVICE)
        }
        _ => io::Error::other(error),
    }
}

/// Builds the output stream for one sample type in the device's `config`, with the clip's frames
/// mapped onto that layout and an output error switching playback off.
fn stream<T: SizedSample + FromSample<f32>>(
    device: &cpal::Device,
    config: StreamConfig,
    shared: Arc<Mutex<Shared>>,
    layout: Device,
) -> Result<cpal::Stream, cpal::Error> {
    let mut play = sink::<T>(shared.clone(), layout);
    let alarms = shared;
    let on_error = move |error: cpal::Error| {
        let mut state = alarms.lock().expect("the player's state");
        state.playing = false;
        state.failed = Some(error.to_string());
    };
    device.build_output_stream(
        config,
        move |data: &mut [T], _: &cpal::OutputCallbackInfo| play(data),
        on_error,
        None,
    )
}

/// The output callback for one sample type: the next frames of the clip in the device's layout,
/// silence otherwise.
fn sink<T: SizedSample + FromSample<f32>>(
    shared: Arc<Mutex<Shared>>,
    device: Device,
) -> impl FnMut(&mut [T]) + Send + 'static {
    move |data: &mut [T]| {
        let mut state = shared.lock().expect("the player's state");
        let frames = state.frames();
        // How many clip frames one output frame takes: 1 when the rates agree.
        let step = f64::from(state.rate.max(1)) / f64::from(device.rate.max(1));
        let mut mixed = Vec::new();
        for frame in data.chunks_mut(device.channels) {
            if !state.playing || state.at >= frames {
                for sample in frame.iter_mut() {
                    *sample = T::from_sample(0.0f32);
                }
                if state.at >= frames {
                    state.playing = false;
                }
                continue;
            }
            mixed.clear();
            mixed.resize(frame.len(), 0.0);
            fill_frame(&state.clip, state.channels, state.at, &mut mixed);
            for (sample, value) in frame.iter_mut().zip(&mixed) {
                *sample = T::from_sample(*value);
            }
            let next = state.at + step;
            state.advance(next);
        }
    }
}

/// One output frame of `device_channels` channels from the clip at `at`: a mono clip fills every
/// channel, a stereo one feeds the matching channel, and a fractional `at` interpolates between the
/// two clip frames it falls between.
fn fill_frame(clip: &[f32], clip_channels: usize, at: f64, frame: &mut [f32]) {
    let clip_channels = clip_channels.max(1);
    let frames = clip.len() / clip_channels;
    if frames == 0 {
        frame.iter_mut().for_each(|sample| *sample = 0.0);
        return;
    }
    let lower = (at.floor() as usize).min(frames - 1);
    let upper = (lower + 1).min(frames - 1);
    let between = (at - at.floor()) as f32;
    for (channel, sample) in frame.iter_mut().enumerate() {
        let source = if clip_channels == 1 {
            0
        } else {
            channel.min(clip_channels - 1)
        };
        let start = clip[lower * clip_channels + source];
        let end = clip[upper * clip_channels + source];
        *sample = start + (end - start) * between;
    }
}

#[cfg(test)]
mod tests {
    use super::{Device, fill_frame, sink};
    use std::sync::{Arc, Mutex};
    use std::time::Duration;

    #[test]
    fn a_mono_clip_fills_every_device_channel_once() {
        let clip = [0.25, -0.5, 0.75];
        let mut frame = [0.0; 2];
        for (index, at) in [0.0, 1.0, 2.0].into_iter().enumerate() {
            fill_frame(&clip, 1, at, &mut frame);
            assert_eq!(
                frame,
                [clip[index], clip[index]],
                "frame {index} is the clip's"
            );
        }
    }

    #[test]
    fn a_stereo_clip_feeds_the_matching_channel() {
        let clip = [0.1, 0.2, 0.3, 0.4];
        let mut frame = [0.0; 2];
        fill_frame(&clip, 2, 0.0, &mut frame);
        assert_eq!(frame, [0.1, 0.2]);
        fill_frame(&clip, 2, 1.0, &mut frame);
        assert_eq!(frame, [0.3, 0.4]);
    }

    #[test]
    fn a_device_at_another_rate_interpolates_between_the_clips_frames() {
        let clip = [0.0, 1.0];
        let mut frame = [0.0; 1];
        // 16 kHz onto 48 kHz: one output frame takes a third of a clip frame.
        fill_frame(&clip, 1, 1.0 / 3.0, &mut frame);
        assert!((frame[0] - 1.0 / 3.0).abs() < 1e-6, "{:?}", frame);
        fill_frame(&clip, 1, 2.0 / 3.0, &mut frame);
        assert!((frame[0] - 2.0 / 3.0).abs() < 1e-6, "{:?}", frame);
        // The last frame holds its value past the clip's end.
        fill_frame(&clip, 1, 1.0, &mut frame);
        assert_eq!(frame, [1.0]);
    }

    #[test]
    fn the_sink_takes_one_clip_frame_per_output_frame_at_the_same_rate() {
        let shared = Arc::new(Mutex::new(super::Shared::silent()));
        {
            let mut state = shared.lock().unwrap();
            state.clip = vec![0.5, 0.5, 0.5, 0.5];
            state.channels = 1;
            state.rate = 16_000;
            state.at = 0.0;
            state.playing = true;
        }
        let device = Device {
            channels: 2,
            rate: 16_000,
        };
        let mut callback = sink::<f32>(shared.clone(), device);
        let mut data = vec![0.0f32; 4 * 2];
        callback(&mut data);
        let state = shared.lock().unwrap();
        assert_eq!(state.at, 4.0, "four output frames took four clip frames");
        assert!(data.iter().all(|sample| *sample == 0.5), "{data:?}");
    }

    /// Item 52's isolated play, which `p` uses: a segment played alone stops at its end - the next
    /// segment never sounds - and the playhead stands back at the segment's start.
    #[test]
    fn a_segment_played_alone_stops_at_its_end_back_at_its_start() {
        let shared = Arc::new(Mutex::new(super::Shared::silent()));
        {
            let mut state = shared.lock().unwrap();
            state.clip = vec![0.5; 8];
            state.channels = 1;
            state.rate = 1_000;
            // Frames 2 and 3: from 2 ms up to 4 ms at one frame a millisecond.
            state.start(Duration::from_millis(2), Some(Duration::from_millis(4)));
        }
        let device = Device {
            channels: 1,
            rate: 1_000,
        };
        let mut callback = sink::<f32>(shared.clone(), device);
        let mut data = vec![1.0f32; 4];
        callback(&mut data);
        let state = shared.lock().unwrap();
        assert_eq!(
            data,
            [0.5, 0.5, 0.0, 0.0],
            "the segment's two frames, then silence"
        );
        assert!(!state.playing, "playback stopped at the segment's end");
        assert_eq!(state.at, 2.0, "the playhead stands at the segment's start");
    }
}
