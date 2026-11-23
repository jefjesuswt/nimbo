// Estado del reproductor embebido: cola (aleatorio, repetir), volumen y el
// enlace con mpv. Las vistas están en player_ui.rs.
use crate::app::App;
use crate::mpv::{Frame, Mpv};
use gpui_kit::component::slider::{SliderEvent, SliderState};
use gpui_kit::*;
use std::sync::Arc;
use std::time::{Duration, Instant};

#[derive(Clone)]
pub struct Track {
    pub file_id: i32,
    pub url: String,
    pub title: String,
    pub artist: String,
    pub folder: String,
    pub thumb: i32,
    pub video: bool,
}

impl Track {
    // Lo que va bajo el título: artista o, si no hay, la carpeta.
    pub fn subtitle(&self) -> String {
        if !self.artist.is_empty() {
            self.artist.clone()
        } else {
            self.folder.rsplit('/').next().unwrap_or("").to_string()
        }
    }
}

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Repeat {
    Off,
    All,
    One,
}

pub struct Player {
    pub mpv: Option<Mpv>,
    pub queue: Vec<Track>,
    // Orden original mientras el aleatorio está activo.
    orig: Option<Vec<Track>>,
    pub at: usize,
    pub repeat: Repeat,
    frames_tx: async_channel::Sender<Frame>,
    pub frame: Option<Arc<RenderImage>>,
    pub pos: f64,
    pub dur: f64,
    pub paused: bool,
    pub has_video: bool,
    pub video_open: bool,
    pub now_open: bool,
    pub volume: f64,
    pub muted: bool,
    pub seek: Entity<SliderState>,
    pub vol: Entity<SliderState>,
    dragging: bool,
    loaded: Instant,
}

pub fn fmt_time(s: f64) -> String {
    let s = s.max(0.0) as u64;
    if s >= 3600 { format!("{}:{:02}:{:02}", s / 3600, s / 60 % 60, s % 60) } else { format!("{}:{:02}", s / 60, s % 60) }
}

// Barajado sin dependencias (xorshift sembrado con el reloj).
fn shuffle<T>(v: &mut [T]) {
    let mut x = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos() as u64 | 1;
    for i in (1..v.len()).rev() {
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        v.swap(i, (x % (i as u64 + 1)) as usize);
    }
}

impl Player {
    // Arranca los bucles de fotogramas y de reloj sobre la vista.
    pub fn new(volume: f64, window: &mut Window, cx: &mut Context<App>) -> Self {
        let (frames_tx, frames_rx) = async_channel::bounded::<Frame>(1);
        let seek = cx.new(|_| SliderState::new().min(0.).max(1000.).step(1.));
        cx.subscribe_in(&seek, window, |this: &mut App, _, ev: &SliderEvent, _, cx| match ev {
            SliderEvent::Change(_) => this.player.dragging = true,
            SliderEvent::Release(v) => {
                let p = &mut this.player;
                p.dragging = false;
                if let Some(m) = &p.mpv {
                    if p.dur > 0. {
                        m.seek(p.dur * v.end() as f64 / 1000.);
                    }
                }
                cx.notify();
            }
        })
        .detach();
        let vol = cx.new(|_| SliderState::new().min(0.).max(100.).step(1.).default_value(volume as f32));
        cx.subscribe_in(&vol, window, |this: &mut App, _, ev: &SliderEvent, _, cx| {
            let v = match ev {
                SliderEvent::Change(v) | SliderEvent::Release(v) => v.end() as f64,
            };
            this.player.set_volume(v);
            if matches!(ev, SliderEvent::Release(_)) {
                this.prefs.volume = v;
                this.prefs.save();
            }
            cx.notify();
        })
        .detach();

        // Fotogramas: imagen nueva y fuera la anterior del atlas de GPUI.
        cx.spawn_in(window, async move |this, cx| {
            while let Ok(f) = frames_rx.recv().await {
                let Some(buf) = image::RgbaImage::from_raw(f.width, f.height, f.bgra) else { continue };
                let img = Arc::new(RenderImage::new(vec![image::Frame::new(buf)]));
                let ok = this.update_in(cx, |this, window, cx| {
                    if let Some(old) = this.player.frame.replace(img) {
                        cx.drop_image(old, Some(window));
                    }
                    cx.notify();
                });
                if ok.is_err() {
                    break;
                }
            }
        })
        .detach();

        // Reloj: posición, duración y pausa cada 250 ms; fin de pista.
        cx.spawn_in(window, async move |this, cx| loop {
            cx.background_executor().timer(Duration::from_millis(250)).await;
            let ok = this.update_in(cx, |this, window, cx| {
                let p = &mut this.player;
                let Some(m) = &p.mpv else { return };
                p.pos = m.position();
                p.dur = m.duration();
                p.paused = m.paused();
                p.has_video = m.has_video();
                // keep-open deja la pista en pausa con eof-reached; justo
                // tras cargar otra puede seguir marcado un instante.
                if m.eof() && p.loaded.elapsed() > Duration::from_secs(1) {
                    if p.at + 1 < p.queue.len() {
                        p.go(p.at + 1);
                    } else if p.repeat == Repeat::All && !p.queue.is_empty() {
                        p.go(0);
                    }
                }
                if !p.dragging && p.dur > 0. {
                    let v = (p.pos / p.dur * 1000.) as f32;
                    p.seek.update(cx, |s, cx| s.set_value(v, window, cx));
                }
                cx.notify();
            });
            if ok.is_err() {
                break;
            }
        })
        .detach();

        Self {
            mpv: None,
            queue: vec![],
            orig: None,
            at: 0,
            repeat: Repeat::Off,
            frames_tx,
            frame: None,
            pos: 0.,
            dur: 0.,
            paused: false,
            has_video: false,
            video_open: false,
            now_open: false,
            volume,
            muted: false,
            seek,
            vol,
            dragging: false,
            loaded: Instant::now(),
        }
    }

    pub fn current(&self) -> Option<&Track> {
        self.queue.get(self.at)
    }

    pub fn shuffled(&self) -> bool {
        self.orig.is_some()
    }

    pub fn play_queue(&mut self, queue: Vec<Track>, at: usize) {
        self.queue = queue;
        self.orig = None;
        let open = self.queue.get(at).map(|t| t.video).unwrap_or(false);
        self.go(at);
        self.video_open = open;
    }

    pub fn go(&mut self, at: usize) {
        let Some(t) = self.queue.get(at).cloned() else { return };
        if self.mpv.is_none() {
            // ponytail: 1280 de ancho máx. por el render por software.
            self.mpv = Mpv::new(self.frames_tx.clone(), 1280);
            self.apply_audio();
            self.apply_repeat();
        }
        if let Some(m) = &self.mpv {
            m.load(&t.url);
        }
        self.loaded = Instant::now();
        self.at = at;
        self.pos = 0.;
        self.dur = 0.;
        self.paused = false;
        // Audio tras vídeo: fuera la vista de vídeo.
        if !t.video {
            self.video_open = false;
        }
    }

    pub fn prev(&mut self) {
        // Como en cualquier reproductor: pasados 3 s, al principio de esta.
        if self.pos > 3. || (self.at == 0 && self.repeat != Repeat::All) {
            if let Some(m) = &self.mpv {
                m.seek(0.);
            }
        } else if self.at == 0 {
            self.go(self.queue.len() - 1);
        } else {
            self.go(self.at - 1);
        }
    }

    pub fn next(&mut self) {
        if self.at + 1 < self.queue.len() {
            self.go(self.at + 1);
        } else if self.repeat == Repeat::All {
            self.go(0);
        }
    }

    pub fn has_next(&self) -> bool {
        self.at + 1 < self.queue.len() || (self.repeat == Repeat::All && self.queue.len() > 1)
    }

    // Aleatorio como en Spotify: la actual se queda y el resto se baraja;
    // al quitarlo vuelve el orden original desde la actual.
    pub fn toggle_shuffle(&mut self) {
        let cur = self.current().map(|t| t.file_id);
        match self.orig.take() {
            Some(orig) => {
                self.queue = orig;
                self.at = self.queue.iter().position(|t| Some(t.file_id) == cur).unwrap_or(0);
            }
            None => {
                if self.queue.len() < 2 {
                    return;
                }
                self.orig = Some(self.queue.clone());
                let now = self.queue.remove(self.at);
                shuffle(&mut self.queue);
                self.queue.insert(0, now);
                self.at = 0;
            }
        }
    }

    pub fn cycle_repeat(&mut self) {
        self.repeat = match self.repeat {
            Repeat::Off => Repeat::All,
            Repeat::All => Repeat::One,
            Repeat::One => Repeat::Off,
        };
        self.apply_repeat();
    }

    fn apply_repeat(&self) {
        if let Some(m) = &self.mpv {
            m.set_string("loop-file", if self.repeat == Repeat::One { "inf" } else { "no" });
        }
    }

    pub fn set_volume(&mut self, v: f64) {
        self.volume = v;
        self.muted = false;
        self.apply_audio();
    }

    pub fn toggle_mute(&mut self) {
        self.muted = !self.muted;
        self.apply_audio();
    }

    fn apply_audio(&self) {
        if let Some(m) = &self.mpv {
            m.set_f64("volume", self.volume);
            m.set_string("mute", if self.muted { "yes" } else { "no" });
        }
    }

    pub fn toggle(&mut self) {
        if let Some(m) = &self.mpv {
            // Al final de la cola, play vuelve a empezar la pista.
            if self.paused && m.eof() {
                m.seek(0.);
            }
            m.set_pause(!self.paused);
            self.paused = !self.paused;
        }
    }

    pub fn stop(&mut self, window: &mut Window, cx: &mut gpui_kit::App) {
        self.mpv = None;
        self.queue.clear();
        self.orig = None;
        self.video_open = false;
        self.now_open = false;
        if let Some(old) = self.frame.take() {
            cx.drop_image(old, Some(window));
        }
    }
}
