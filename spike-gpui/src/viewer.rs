// Visor de imágenes: la imagen completa (se baja al abrirla), anterior/siguiente.
use crate::app::{App, icon};
use crate::tg::{Cmd, Item};
use gpui_kit::component::button::*;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;

impl App {
    pub fn open_viewer(&mut self, imgs: Vec<Item>, at: usize, cx: &mut Context<Self>) {
        self.viewer = Some((imgs, at));
        self.want_viewer();
        cx.notify();
    }

    // La actual y la siguiente, para que pasar sea instantáneo.
    fn want_viewer(&self) {
        let Some((imgs, at)) = &self.viewer else { return };
        for f in imgs.iter().skip(*at).take(2) {
            if !self.local.contains_key(&f.file_id) && self.asked.borrow_mut().insert(f.file_id) {
                let _ = self.cmd.send(Cmd::Fetch(f.file_id));
            }
        }
    }

    fn step_viewer(&mut self, d: isize, cx: &mut Context<Self>) {
        if let Some((imgs, at)) = &mut self.viewer {
            let n = imgs.len() as isize;
            *at = ((*at as isize + d).rem_euclid(n)) as usize;
        }
        self.want_viewer();
        cx.notify();
    }

    pub fn viewer_view(&mut self, cx: &mut Context<Self>) -> Div {
        let (imgs, at) = self.viewer.clone().unwrap();
        let f = &imgs[at];
        // Mientras llega la completa, la miniatura.
        let path = self.local.get(&f.file_id).or_else(|| self.local.get(&f.thumb)).cloned();
        let full = self.local.contains_key(&f.file_id);
        let many = imgs.len() > 1;
        let fg = gpui_kit::white();
        div()
            .size_full()
            .relative()
            .bg(gpui_kit::black())
            .flex()
            .items_center()
            .justify_center()
            .map(|d| match path {
                Some(p) => d.child(img(p).size_full().object_fit(ObjectFit::Contain)),
                None => d.child(div().text_color(fg).child("Loading…")),
            })
            .child(
                div()
                    .absolute()
                    .top_0()
                    .left_0()
                    .right_0()
                    .h_flex()
                    .p_3()
                    .gap_3()
                    .items_center()
                    .bg(hsla(0., 0., 0., 0.45))
                    .text_color(fg)
                    .child(Button::new("close-viewer").ghost().icon(icon("x")).tooltip("Close")
                        .on_click(cx.listener(|this, _, _, cx| { this.viewer = None; cx.notify(); })))
                    .child(div().flex_1().overflow_hidden().whitespace_nowrap().text_ellipsis().child(f.name.clone()))
                    .when(!full, |d| d.child(div().text_size(px(12.)).opacity(0.7).child("Loading full size…")))
                    .child(div().text_size(px(12.)).opacity(0.7).child(format!("{} / {}", at + 1, imgs.len())))
                    .child({
                        let f = f.clone();
                        Button::new("dl-img").ghost().icon(icon("download")).tooltip("Download")
                            .on_click(cx.listener(move |this, _, _, cx| this.download(vec![f.clone()], None, cx)))
                    }),
            )
            .when(many, |d| {
                d.child(
                    div().absolute().left_3().child(
                        Button::new("prev-img").ghost().large().icon(icon("chevron-left"))
                            .on_click(cx.listener(|this, _, _, cx| this.step_viewer(-1, cx))),
                    ),
                )
                .child(
                    div().absolute().right_3().child(
                        Button::new("next-img").ghost().large().icon(icon("chevron-right"))
                            .on_click(cx.listener(|this, _, _, cx| this.step_viewer(1, cx))),
                    ),
                )
            })
    }
}
