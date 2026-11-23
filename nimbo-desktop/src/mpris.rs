// MPRIS por D-Bus (souvlaki sobre zbus): teclas multimedia, playerctl,
// Waybar y el widget del escritorio ven y controlan el reproductor.
use crate::app::App;
use souvlaki::{MediaControlEvent, MediaControls, MediaMetadata, MediaPlayback, MediaPosition, PlatformConfig, SeekDirection};
use std::time::{Duration, Instant};

pub struct Mpris {
    controls: MediaControls,
    // Lo último publicado, para no repetir en cada tic del reloj.
    track: Option<i32>,
    paused: Option<bool>,
    cover: bool,
    dur: bool,
    at: Instant,
}

impl Mpris {
    pub fn start(window: &mut gpui_kit::Window, cx: &mut gpui_kit::Context<App>) -> Option<Self> {
        let mut controls = MediaControls::new(PlatformConfig { display_name: "Nimbo", dbus_name: "nimbo", hwnd: None }).ok()?;
        let (tx, rx) = async_channel::unbounded::<MediaControlEvent>();
        controls.attach(move |e| { let _ = tx.send_blocking(e); }).ok()?;
        cx.spawn_in(window, async move |this, cx| {
            while let Ok(e) = rx.recv().await {
                let ok = this.update_in(cx, |this, window, cx| {
                    let p = &mut this.player;
                    match e {
                        MediaControlEvent::Play if p.paused => p.toggle(),
                        MediaControlEvent::Pause if !p.paused => p.toggle(),
                        MediaControlEvent::Toggle => p.toggle(),
                        MediaControlEvent::Next => p.next(),
                        MediaControlEvent::Previous => p.prev(),
                        MediaControlEvent::Stop => p.stop(window, cx),
                        MediaControlEvent::Seek(d) => p.skip(if d == SeekDirection::Forward { 10. } else { -10. }),
                        MediaControlEvent::SeekBy(d, by) => {
                            let s = by.as_secs_f64();
                            p.skip(if d == SeekDirection::Forward { s } else { -s })
                        }
                        MediaControlEvent::SetPosition(MediaPosition(at)) => p.seek_to(at.as_secs_f64()),
                        MediaControlEvent::SetVolume(v) => p.set_volume((v * 100.).clamp(0., 100.)),
                        MediaControlEvent::Raise => window.activate_window(),
                        _ => {}
                    }
                    cx.notify();
                });
                if ok.is_err() {
                    break;
                }
            }
        })
        .detach();
        Some(Self { controls, track: None, paused: None, cover: false, dur: false, at: Instant::now() })
    }
}

impl App {
    // Publica pista, carátula y estado cuando cambian (y la posición cada 2 s).
    pub fn sync_mpris(&mut self) {
        let cur = self.player.current().cloned();
        let cover = cur.as_ref().and_then(|t| self.thumb_path(t.thumb));
        let Some(m) = self.mpris.as_mut() else { return };
        let Some(t) = cur else {
            if m.track.take().is_some() {
                let _ = m.controls.set_playback(MediaPlayback::Stopped);
            }
            return;
        };
        // Pista nueva, o ya llegaron la carátula o la duración.
        if m.track != Some(t.file_id) || (!m.cover && cover.is_some()) || (!m.dur && self.player.dur > 0.) {
            let url = cover.as_ref().map(|p| format!("file://{}", p.display()));
            let sub = t.subtitle();
            let album = t.folder.rsplit('/').next().unwrap_or("").to_string();
            let _ = m.controls.set_metadata(MediaMetadata {
                title: Some(&t.title),
                artist: (!sub.is_empty()).then_some(sub.as_str()),
                album: (!album.is_empty()).then_some(album.as_str()),
                cover_url: url.as_deref(),
                duration: (self.player.dur > 0.).then(|| Duration::from_secs_f64(self.player.dur)),
            });
            m.track = Some(t.file_id);
            m.cover = cover.is_some();
            m.dur = self.player.dur > 0.;
            m.paused = None;
        }
        let paused = self.player.paused;
        if m.paused != Some(paused) || m.at.elapsed() > Duration::from_secs(2) {
            let progress = Some(MediaPosition(Duration::from_secs_f64(self.player.pos.max(0.))));
            let _ = m.controls.set_playback(if paused { MediaPlayback::Paused { progress } } else { MediaPlayback::Playing { progress } });
            m.paused = Some(paused);
            m.at = Instant::now();
        }
    }
}
