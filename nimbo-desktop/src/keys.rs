// Atajos de teclado. Los de una sola tecla no valen mientras se escribe en
// un campo (`!Input`).
use crate::app::{App, Section};
use gpui_kit::*;

gpui_kit::actions!(nimbo, [PlayPause, SeekBack, SeekFwd, PrevTrack, NextTrack, Back, Find, GoUp, Fullscreen]);

pub const CONTEXT: &str = "Nimbo";

pub fn bind(cx: &mut gpui_kit::App) {
    let free = Some("Nimbo && !Input");
    cx.bind_keys([
        KeyBinding::new("space", PlayPause, free),
        KeyBinding::new("left", SeekBack, free),
        KeyBinding::new("right", SeekFwd, free),
        KeyBinding::new("ctrl-left", PrevTrack, free),
        KeyBinding::new("ctrl-right", NextTrack, free),
        KeyBinding::new("backspace", GoUp, free),
        KeyBinding::new("f", Fullscreen, free),
        KeyBinding::new("alt-left", GoUp, Some(CONTEXT)),
        KeyBinding::new("escape", Back, Some(CONTEXT)),
        KeyBinding::new("ctrl-f", Find, Some(CONTEXT)),
    ]);
}

impl App {
    // Engancha los atajos a la raíz de la ventana.
    pub fn keyed(&mut self, root: Div, cx: &mut Context<Self>) -> Stateful<Div> {
        root.id("root")
            .key_context(CONTEXT)
            .track_focus(&self.focus)
            .on_action(cx.listener(|this, _: &PlayPause, _, cx| {
                if this.player.current().is_some() {
                    this.player.toggle();
                    cx.notify();
                }
            }))
            .on_action(cx.listener(|this, _: &SeekBack, _, cx| this.arrow(-1, cx)))
            .on_action(cx.listener(|this, _: &SeekFwd, _, cx| this.arrow(1, cx)))
            .on_action(cx.listener(|this, _: &PrevTrack, _, cx| { this.player.prev(); cx.notify(); }))
            .on_action(cx.listener(|this, _: &NextTrack, _, cx| { this.player.next(); cx.notify(); }))
            .on_action(cx.listener(|this, _: &GoUp, _, cx| {
                if this.section == Section::Files && !this.dir.is_empty() && !this.overlay_open() {
                    let up = this.dir.rsplit_once('/').map(|(a, _)| a.to_string()).unwrap_or_default();
                    this.open_dir(up, cx);
                } else if this.section == Section::Music && this.album.is_some() {
                    this.album = None;
                    cx.notify();
                }
            }))
            .on_action(cx.listener(|this, _: &Fullscreen, window, _| {
                if this.player.video_open || this.viewer.is_some() {
                    window.toggle_fullscreen();
                }
            }))
            .on_action(cx.listener(|this, _: &Find, window, cx| {
                if this.section != Section::Settings {
                    this.search.update(cx, |s, cx| s.focus(window, cx));
                }
            }))
            .on_action(cx.listener(|this, _: &Back, window, cx| this.back(window, cx)))
    }

    fn overlay_open(&self) -> bool {
        self.modal.is_some() || self.viewer.is_some() || self.text.is_some() || self.player.now_open || self.player.video_open
    }

    // Flechas: en el visor, foto anterior/siguiente; si suena algo, ±5 s.
    fn arrow(&mut self, d: isize, cx: &mut Context<Self>) {
        if self.viewer.is_some() {
            self.step_viewer(d, cx);
        } else if self.player.current().is_some() {
            self.player.skip(5. * d as f64);
            cx.notify();
        }
    }

    // Esc cierra lo de más arriba: diálogo, visores, Now playing, vídeo,
    // selección, búsqueda.
    fn back(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.modal.is_some() {
            self.close_modal(cx);
        } else if self.text.is_some() {
            self.text = None;
        } else if self.viewer.is_some() {
            self.viewer = None;
        } else if self.player.now_open {
            self.player.now_open = false;
        } else if self.player.video_open {
            if window.is_fullscreen() {
                window.toggle_fullscreen();
            } else {
                self.player.video_open = false;
            }
        } else if !self.selected.is_empty() {
            self.selected.clear();
        } else if !self.search.read(cx).value().is_empty() {
            self.search.update(cx, |s, cx| s.set_value("", window, cx));
        }
        window.focus(&self.focus, cx);
        cx.notify();
    }
}
