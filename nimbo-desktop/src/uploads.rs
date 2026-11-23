// Panel de subidas, abajo a la derecha: cuántas van y las últimas.
use crate::app::{App, icon};
use crate::tg::UpState;
use gpui_kit::component::button::*;
use gpui_kit::component::progress::Progress;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;

impl App {
    pub fn upload_panel(&mut self, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let total = self.uploads.len();
        let done = self.uploads.iter().filter(|u| u.state == UpState::Done).count();
        let failed = self.uploads.iter().filter(|u| u.state == UpState::Failed).count();
        let busy = done + failed < total;
        let running_down = self.uploads.iter().find(|u| matches!(u.state, UpState::Running(_) | UpState::Queued)).map(|u| u.down).unwrap_or(false);
        let any_down = self.uploads.iter().any(|u| u.down && u.state == UpState::Done);
        let title = if busy {
            format!("{} {} of {total}", if running_down { "Downloading" } else { "Uploading" }, done + failed + 1)
        } else if failed > 0 {
            format!("{done} done, {failed} failed")
        } else {
            format!("{done} done")
        };
        // Primero lo que está en marcha, luego lo último terminado.
        let mut shown: Vec<_> = self.uploads.iter().filter(|u| matches!(u.state, UpState::Running(_))).cloned().collect();
        shown.extend(self.uploads.iter().rev().filter(|u| !matches!(u.state, UpState::Running(_))).take(5).cloned());
        let bottom = if self.player.current().is_some() { px(96.) } else { px(16.) };
        div()
            .absolute()
            .right_4()
            .bottom(bottom)
            .w(px(340.))
            .v_flex()
            .p_3()
            .gap_2()
            .rounded(px(14.))
            .border_1()
            .border_color(t.border)
            .bg(t.popover)
            .shadow_lg()
            .child(
                div()
                    .h_flex()
                    .items_center()
                    .gap_2()
                    .child(icon(if !busy { "check" } else if running_down { "download" } else { "cloud-upload" }).with_size(px(16.)))
                    .child(div().flex_1().font_weight(FontWeight::MEDIUM).child(title))
                    .when(any_down, |d| {
                        d.child(Button::new("open-dl").ghost().small().icon(icon("folder-open")).tooltip("Show downloads")
                            .on_click(cx.listener(|this, _, _, _| open_path(&this.prefs.downloads))))
                    })
                    .when(!busy, |d| {
                        d.child(Button::new("clear-up").ghost().small().icon(icon("x")).tooltip("Dismiss")
                            .on_click(cx.listener(|this, _, _, cx| { this.uploads.clear(); cx.notify(); })))
                    }),
            )
            .children(shown.into_iter().map(|u| {
                let (ic, color) = match u.state {
                    UpState::Done => ("check", t.success),
                    UpState::Failed => ("circle-alert", t.danger),
                    _ if u.down => ("download", t.muted_foreground),
                    _ => ("cloud-upload", t.muted_foreground),
                };
                // Subida: carpeta de la nube. Descarga: última carpeta local.
                let to = if u.down {
                    u.folder.rsplit('/').next().unwrap_or("").to_string()
                } else if u.folder.is_empty() {
                    "Files".to_string()
                } else {
                    u.folder.clone()
                };
                div()
                    .v_flex()
                    .gap_1()
                    .child(
                        div()
                            .h_flex()
                            .gap_2()
                            .items_center()
                            .child(icon(ic).with_size(px(14.)).text_color(color))
                            .child(div().flex_1().overflow_hidden().whitespace_nowrap().text_ellipsis().text_size(px(13.)).child(u.name.clone()))
                            .child(div().text_size(px(11.)).text_color(t.muted_foreground).whitespace_nowrap().child(to)),
                    )
                    .when_some(match u.state { UpState::Running(f) => Some(f), _ => None }, |d, f| {
                        d.child(Progress::new(("up", u.key as usize)).value(f * 100.))
                    })
            }))
    }
}

// Abre una carpeta en el gestor de archivos.
pub fn open_path(p: &std::path::Path) {
    let _ = std::fs::create_dir_all(p);
    let _ = std::process::Command::new("xdg-open").arg(p).spawn();
}
