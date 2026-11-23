// Vistas del reproductor: barra inferior (estilo Spotify), «Reproduciendo»
// a pantalla completa con la cola, y la vista de vídeo.
use crate::app::{App, icon};
use crate::player::{Repeat, fmt_time};
use gpui_kit::component::button::*;
use gpui_kit::component::slider::Slider;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;
use std::path::PathBuf;

impl App {
    // Carátula (miniatura en disco) o un disco con la nota.
    fn cover(&self, thumb: Option<PathBuf>, size: Pixels, radius: Pixels, cx: &gpui_kit::App) -> Div {
        let t = cx.theme();
        div()
            .size(size)
            .flex_none()
            .rounded(radius)
            .overflow_hidden()
            .bg(t.secondary)
            .flex()
            .items_center()
            .justify_center()
            .map(|d| match thumb {
                Some(p) => d.child(img(p).size_full().object_fit(ObjectFit::Cover)),
                None => d.child(icon("music").with_size(size * 0.4).text_color(t.muted_foreground)),
            })
    }

    // Botón de modo (aleatorio/repetir): resaltado si está activo.
    fn mode_btn(id: &'static str, ic: &'static str, tip: &'static str, on: bool) -> Button {
        Button::new(id).ghost().icon(icon(ic)).tooltip(tip).selected(on)
    }

    // Aleatorio, anterior, play/pausa, siguiente, repetir.
    fn transport(&mut self, big: bool, cx: &mut Context<Self>) -> Div {
        let p = &self.player;
        let repeat_icon = if p.repeat == Repeat::One { "repeat-1" } else { "repeat" };
        let repeat_tip = match p.repeat {
            Repeat::Off => "Repeat: off",
            Repeat::All => "Repeat: all",
            Repeat::One => "Repeat: this track",
        };
        let play = Button::new(if big { "toggle-big" } else { "toggle" })
            .primary()
            .rounded(px(999.))
            .icon(icon(if p.paused { "play" } else { "pause" }))
            .when(big, |b| b.large())
            .on_click(cx.listener(|this, _, _, cx| { this.player.toggle(); cx.notify(); }));
        div()
            .h_flex()
            .gap(if big { px(14.) } else { px(6.) })
            .items_center()
            .justify_center()
            .child(Self::mode_btn(if big { "shuffle-big" } else { "shuffle" }, "shuffle", "Shuffle", p.shuffled())
                .on_click(cx.listener(|this, _, _, cx| { this.player.toggle_shuffle(); cx.notify(); })))
            .child(Button::new(if big { "prev-big" } else { "prev" }).ghost().icon(icon("skip-back")).tooltip("Previous")
                .on_click(cx.listener(|this, _, _, cx| { this.player.prev(); cx.notify(); })))
            .child(play)
            .child(Button::new(if big { "next-big" } else { "next" }).ghost().icon(icon("skip-forward")).tooltip("Next")
                .disabled(!p.has_next())
                .on_click(cx.listener(|this, _, _, cx| { this.player.next(); cx.notify(); })))
            .child(Self::mode_btn(if big { "repeat-big" } else { "repeat" }, repeat_icon, repeat_tip, p.repeat != Repeat::Off)
                .on_click(cx.listener(|this, _, _, cx| { this.player.cycle_repeat(); cx.notify(); })))
    }

    fn seek_row(&self, cx: &gpui_kit::App) -> Div {
        let t = cx.theme();
        let p = &self.player;
        let time = |s: f64| div().w(px(44.)).flex_none().text_size(px(11.)).text_color(t.muted_foreground).child(fmt_time(s));
        div()
            .h_flex()
            .w_full()
            .gap_2()
            .items_center()
            .child(time(p.pos).text_right())
            .child(div().flex_1().min_w_0().child(Slider::new(&p.seek).horizontal()))
            .child(time(p.dur))
    }

    fn volume(&mut self, id: &'static str, cx: &mut Context<Self>) -> Div {
        let mute = self.mute_btn(id, cx);
        div()
            .h_flex()
            .flex_none()
            .gap_1()
            .items_center()
            .child(mute)
            .child(div().w(px(96.)).child(Slider::new(&self.player.vol).horizontal()))
    }

    // Barra inferior. Ancha: pista | controles + avance | cola, volumen.
    // Estrecha (< 860 px): avance arriba a todo el ancho y debajo pista |
    // anterior/play/siguiente | cola, silencio, cerrar. Aleatorio, repetir y
    // el volumen fino quedan en «Now playing».
    pub fn player_bar(&mut self, width: Pixels, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        let Some(track) = self.player.current().cloned() else { return div() };
        let wide = width >= px(860.);
        let thumb = self.thumb_path(track.thumb);
        let video = self.player.has_video;
        let info = div()
            .id("now-info")
            .h_flex()
            .when(wide, |d| d.w(px(260.)).flex_none())
            .when(!wide, |d| d.flex_1())
            .min_w_0()
            .gap_3()
            .items_center()
            .overflow_hidden()
            .cursor_pointer()
            .child(self.cover(thumb, px(if wide { 52. } else { 44. }), px(8.), cx))
            .child(
                div()
                    .v_flex()
                    .flex_1()
                    .min_w_0()
                    .overflow_hidden()
                    .child(div().whitespace_nowrap().text_ellipsis().font_weight(FontWeight::MEDIUM).child(track.title.clone()))
                    .child(div().text_size(px(12.)).whitespace_nowrap().text_ellipsis().text_color(t.muted_foreground).child(track.subtitle())),
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_now(cx)));
        let side = div()
            .h_flex()
            .flex_none()
            .gap_1()
            .items_center()
            .child(Button::new("queue").ghost().icon(icon("list-music")).tooltip("Queue")
                .selected(self.player.now_open)
                .on_click(cx.listener(|this, _, _, cx| {
                    if this.player.now_open { this.player.now_open = false; cx.notify(); } else { this.open_now(cx) }
                })))
            .when(video && !self.player.video_open, |d| {
                d.child(Button::new("show").ghost().icon(icon("maximize-2")).tooltip("Show video")
                    .on_click(cx.listener(|this, _, _, cx| { this.player.video_open = true; cx.notify(); })))
            })
            .when(video && self.player.video_open, |d| {
                d.child(Button::new("hide-v").ghost().icon(icon("minimize-2")).tooltip("Back to library")
                    .on_click(cx.listener(|this, _, _, cx| { this.player.video_open = false; cx.notify(); })))
            })
            .map(|d| if wide { d.child(self.volume("mute", cx)) } else { d.child(self.mute_btn("mute", cx)) })
            .child(Button::new("close").ghost().icon(icon("x")).tooltip("Stop")
                .on_click(cx.listener(|this, _, window, cx| { this.player.stop(window, cx); cx.notify(); })));
        let bar = div().w_full().flex_none().border_t_1().border_color(t.border).bg(t.sidebar);
        if wide {
            return bar
                .h_flex()
                .h(px(84.))
                .px_4()
                .gap_4()
                .items_center()
                .child(info)
                .child(
                    div()
                        .v_flex()
                        .flex_1()
                        .min_w_0()
                        .gap_1()
                        .items_center()
                        .child(self.transport(false, cx).flex_none())
                        .child(div().w_full().max_w(px(620.)).child(self.seek_row(cx))),
                )
                .child(side);
        }
        bar.v_flex()
            .px_3()
            .pt_1()
            .pb_2()
            .gap_1()
            .child(self.seek_row(cx))
            .child(
                div()
                    .h_flex()
                    .gap_2()
                    .items_center()
                    .child(info)
                    .child(self.transport_compact(cx))
                    .child(side),
            )
    }

    // Solo anterior / play / siguiente (barra estrecha).
    fn transport_compact(&mut self, cx: &mut Context<Self>) -> Div {
        let p = &self.player;
        div()
            .h_flex()
            .flex_none()
            .gap_1()
            .items_center()
            .child(Button::new("prev").ghost().icon(icon("skip-back")).tooltip("Previous")
                .on_click(cx.listener(|this, _, _, cx| { this.player.prev(); cx.notify(); })))
            .child(Button::new("toggle").primary().rounded(px(999.)).icon(icon(if p.paused { "play" } else { "pause" }))
                .on_click(cx.listener(|this, _, _, cx| { this.player.toggle(); cx.notify(); })))
            .child(Button::new("next").ghost().icon(icon("skip-forward")).tooltip("Next").disabled(!p.has_next())
                .on_click(cx.listener(|this, _, _, cx| { this.player.next(); cx.notify(); })))
    }

    fn mute_btn(&mut self, id: &'static str, cx: &mut Context<Self>) -> Button {
        let p = &self.player;
        let ic = if p.muted || p.volume == 0. { "volume-x" } else if p.volume < 50. { "volume-1" } else { "volume-2" };
        Button::new(id).ghost().icon(icon(ic)).tooltip(if p.muted { "Unmute" } else { "Mute" })
            .on_click(cx.listener(|this, _, _, cx| { this.player.toggle_mute(); cx.notify(); }))
    }

    fn open_now(&mut self, cx: &mut Context<Self>) {
        if self.player.has_video {
            self.player.video_open = true;
        } else {
            self.player.now_open = true;
        }
        cx.notify();
    }

    // «Reproduciendo»: carátula grande y controles a la izquierda, la cola
    // («A continuación») a la derecha.
    pub fn now_view(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        // Estrecha: sin la columna de la cola; la carátula según lo que quepa.
        let vp = window.viewport_size();
        let narrow = vp.width < px(900.);
        let art = px(340.).min(vp.height - px(330.)).min(vp.width - px(64.)).max(px(120.));
        let Some(track) = self.player.current().cloned() else { return div() };
        let thumb = self.thumb_path(track.thumb);
        let queue = self.player.queue.clone();
        let at = self.player.at;
        let rows: Vec<_> = queue
            .iter()
            .enumerate()
            .map(|(i, q)| {
                let on = i == at;
                let th = self.thumb_path(q.thumb);
                div()
                    .id(("q", i))
                    .h_flex()
                    .gap_3()
                    .px_2()
                    .py_1()
                    .items_center()
                    .rounded(px(8.))
                    .cursor_pointer()
                    .when(on, |d| d.bg(t.secondary))
                    .hover(|s| s.bg(t.secondary))
                    .child(self.cover(th, px(40.), px(6.), cx))
                    .child(
                        div()
                            .v_flex()
                            .flex_1()
                            .overflow_hidden()
                            .child(div().whitespace_nowrap().text_ellipsis().text_size(px(13.))
                                .when(on, |d| d.font_weight(FontWeight::SEMIBOLD).text_color(t.primary))
                                .child(q.title.clone()))
                            .child(div().whitespace_nowrap().text_ellipsis().text_size(px(11.)).text_color(t.muted_foreground).child(q.subtitle())),
                    )
                    .when(on, |d| d.child(icon(if self.player.paused { "pause" } else { "volume-2" }).with_size(px(14.)).text_color(t.primary)))
                    .on_click(cx.listener(move |this, _, _, cx| { this.player.go(i); cx.notify(); }))
            })
            .collect();
        let left = div()
            .v_flex()
            .flex_1()
            .min_w_0()
            .items_center()
            .justify_center()
            .gap_5()
            .px(if narrow { px(20.) } else { px(32.) })
            .child(self.cover(thumb, art, px(16.), cx).shadow_lg())
            .child(
                div()
                    .v_flex()
                    .w_full()
                    .max_w(px(420.))
                    .items_center()
                    .gap_1()
                    .child(div().w_full().text_center().text_size(px(22.)).font_weight(FontWeight::SEMIBOLD).whitespace_nowrap().text_ellipsis().child(track.title.clone()))
                    .child(div().w_full().text_center().text_color(t.muted_foreground).whitespace_nowrap().text_ellipsis().child(track.subtitle())),
            )
            .child(div().w_full().max_w(px(460.)).child(self.seek_row(cx)))
            .child(self.transport(true, cx))
            .child(self.volume("mute-big", cx));
        let up_next = queue.len().saturating_sub(at + 1);
        div()
            .size_full()
            .v_flex()
            .child(
                div()
                    .h_flex()
                    .h(px(56.))
                    .flex_none()
                    .px_3()
                    .gap_2()
                    .items_center()
                    .child(Button::new("now-close").ghost().icon(icon("chevron-down")).tooltip("Close")
                        .on_click(cx.listener(|this, _, _, cx| { this.player.now_open = false; cx.notify(); })))
                    .child(div().font_weight(FontWeight::MEDIUM).child("Now playing"))
                    .child(div().flex_1())
                    .child(div().text_size(px(12.)).text_color(t.muted_foreground)
                        .child(format!("{} of {}", at + 1, queue.len()))),
            )
            .child(
                div()
                    .h_flex()
                    .flex_1()
                    .min_h_0()
                    .child(left)
                    .when(!narrow, |d| d.child(
                        div()
                            .v_flex()
                            .w(px(340.))
                            .flex_none()
                            .h_full()
                            .border_l_1()
                            .border_color(t.border)
                            .child(
                                div().h_flex().px_4().py_3().items_center()
                                    .child(div().flex_1().font_weight(FontWeight::SEMIBOLD).child("Queue"))
                                    .child(div().text_size(px(12.)).text_color(t.muted_foreground).child(format!("{up_next} up next"))),
                            )
                            .child(div().id("queue-list").flex_1().min_h_0().overflow_y_scroll().px_2().pb_2().children(rows)),
                    )),
            )
    }

    // Vídeo a toda la ventana, con la barra debajo (sin recortarla).
    pub fn video_view(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Div {
        let width = window.viewport_size().width;
        let frame = self.player.frame.clone();
        div()
            .v_flex()
            .size_full()
            .bg(gpui_kit::black())
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .overflow_hidden()
                    .relative()
                    .flex()
                    .items_center()
                    .justify_center()
                    .when_some(frame, |d, f| d.child(img(f).size_full().object_fit(ObjectFit::Contain)))
                    .when(self.player.frame.is_none(), |d| d.child(div().text_color(gpui_kit::white()).child("Buffering…")))
                    .child(
                        div().absolute().top_3().left_3().child(
                            Button::new("hide").ghost().icon(icon("arrow-left")).label("Library")
                                .on_click(cx.listener(|this, _, _, cx| { this.player.video_open = false; cx.notify(); })),
                        ),
                    ),
            )
            .child(self.player_bar(width, cx))
    }
}
