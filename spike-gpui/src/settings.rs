// Ajustes: apariencia, vista, caché local, cuenta y versión.
use crate::app::{App, icon};
use crate::library::fmt_size;
use crate::prefs::{CACHE_CHOICES, ThemePref};
use crate::uploads::open_path;
use crate::tg::Cmd;
use gpui_kit::component::button::*;
use gpui_kit::component::*;
use gpui_kit::*;

fn group(cx: &gpui_kit::App, title: &str) -> Div {
    let t = cx.theme();
    div()
        .v_flex()
        .gap_2()
        .child(div().px_1().text_size(px(13.)).font_weight(FontWeight::SEMIBOLD).text_color(t.muted_foreground).child(title.to_string()))
}

fn card(cx: &gpui_kit::App) -> Div {
    let t = cx.theme();
    div().v_flex().rounded(px(12.)).border_1().border_color(t.border).bg(t.popover)
}

// Fila: título y explicación a la izquierda, control a la derecha.
fn item(cx: &gpui_kit::App, title: &str, sub: &str, control: impl IntoElement, last: bool) -> Div {
    let t = cx.theme();
    let d = div()
        .h_flex()
        .px_4()
        .py_3()
        .gap_4()
        .items_center()
        .child(
            div()
                .v_flex()
                .flex_1()
                .min_w_0()
                .child(div().child(title.to_string()))
                .child(div().text_size(px(12.)).text_color(t.muted_foreground).child(sub.to_string())),
        )
        .child(div().flex_none().child(control));
    if last { d } else { d.border_b_1().border_color(t.border) }
}

impl App {
    pub fn settings(&mut self, cx: &mut Context<Self>) -> impl IntoElement {
        let theme = self.prefs.theme;
        let grid = self.prefs.grid;
        let seg = |id: &'static str, label: &'static str, ic: &'static str, on: bool| {
            Button::new(id).ghost().small().icon(icon(ic)).label(label).selected(on)
        };
        let theme_ctl = div()
            .h_flex()
            .gap_1()
            .child(seg("t-sys", "System", "monitor", theme == ThemePref::System)
                .on_click(cx.listener(|this, _, w, cx| this.set_theme(ThemePref::System, w, cx))))
            .child(seg("t-light", "Light", "sun", theme == ThemePref::Light)
                .on_click(cx.listener(|this, _, w, cx| this.set_theme(ThemePref::Light, w, cx))))
            .child(seg("t-dark", "Dark", "moon", theme == ThemePref::Dark)
                .on_click(cx.listener(|this, _, w, cx| this.set_theme(ThemePref::Dark, w, cx))));
        let view_ctl = div()
            .h_flex()
            .gap_1()
            .child(seg("v-list", "List", "list", !grid)
                .on_click(cx.listener(|this, _, _, cx| { this.prefs.grid = false; this.prefs.save(); cx.notify(); })))
            .child(seg("v-grid", "Grid", "layout-grid", grid)
                .on_click(cx.listener(|this, _, _, cx| { this.prefs.grid = true; this.prefs.save(); cx.notify(); })));
        let used = self.storage.map(fmt_size).unwrap_or_else(|| "…".into());
        let clear = Button::new("clear-cache").outline().icon(icon("brush-cleaning")).label("Clear cache")
            .on_click(cx.listener(|this, _, _, cx| {
                this.storage = None;
                let _ = this.cmd.send(Cmd::ClearCache);
                cx.notify();
            }));
        let cap = self.prefs.cache_mb;
        let mut cap_ctl = div().h_flex().gap_1();
        for (i, (mb, label)) in CACHE_CHOICES.iter().copied().enumerate() {
            cap_ctl = cap_ctl.child(Button::new(("cap", i)).ghost().small().label(label).selected(cap == mb)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.prefs.cache_mb = mb;
                    this.prefs.save();
                    let _ = this.cmd.send(Cmd::SetCap(this.prefs.cache_bytes()));
                    let _ = this.cmd.send(Cmd::Storage);
                    cx.notify();
                })));
        }
        let dl = self.prefs.downloads.display().to_string();
        let dl_ctl = div()
            .h_flex()
            .gap_1()
            .child(Button::new("dl-open").ghost().small().icon(icon("folder-open")).tooltip("Open")
                .on_click(cx.listener(|this, _, _, _| open_path(&this.prefs.downloads))))
            .child(Button::new("dl-change").outline().small().label("Change…")
                .on_click(cx.listener(|this, _, _, cx| this.pick_downloads(cx))));
        let (name, phone) = self.me.clone().unwrap_or_default();
        let logout = Button::new("logout").danger().icon(icon("log-out")).label("Log out")
            .on_click(cx.listener(|this, _, _, _| { let _ = this.cmd.send(Cmd::LogOut); }));

        div()
            .id("settings")
            .flex_1()
            .overflow_y_scroll()
            .child(
                div()
                    .v_flex()
                    .w_full()
                    .max_w(px(640.))
                    .mx_auto()
                    .px_6()
                    .py_6()
                    .gap_6()
                    .child(div().text_size(px(22.)).font_weight(FontWeight::SEMIBOLD).child("Settings"))
                    .child(group(cx, "Appearance").child(card(cx)
                        .child(item(cx, "Theme", "Follow the system or pick one", theme_ctl, false))
                        .child(item(cx, "Default view", "How folders and media are shown", view_ctl, true))))
                    .child(group(cx, "Storage").child(card(cx)
                        .child(item(cx, "Cache limit",
                            "Oldest go first; unused for 7 days are removed.", cap_ctl, false))
                        .child(item(cx, &format!("Cache on this computer: {used}"),
                            "Thumbnails and streamed files. Your files stay in Telegram.", clear, true))))
                    .child(group(cx, "Downloads").child(card(cx)
                        .child(item(cx, "Save downloads to", &dl, dl_ctl, true))))
                    .child(group(cx, "Account").child(card(cx)
                        .child(item(cx, if name.is_empty() { "Telegram" } else { &name }, &phone, logout, true))))
                    .child(group(cx, "About").child(card(cx)
                        .child(item(cx, "Nimbo Desktop 0.0.1 (spike)",
                            "GPUI · TDLib 1.8.61 · libmpv. Same cloud format as the phone app.", div(), true)))),
            )
    }

    fn pick_downloads(&mut self, cx: &mut Context<Self>) {
        let rx = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some("Choose".into()),
        });
        cx.spawn(async move |this, cx| {
            if let Ok(Ok(Some(mut paths))) = rx.await {
                if let Some(p) = paths.pop() {
                    let _ = this.update(cx, |this, cx| {
                        this.prefs.downloads = p;
                        this.prefs.save();
                        cx.notify();
                    });
                }
            }
        })
        .detach();
    }

    fn set_theme(&mut self, pref: ThemePref, window: &mut Window, cx: &mut Context<Self>) {
        self.prefs.theme = pref;
        self.prefs.save();
        self.apply_theme(window, cx);
        cx.notify();
    }
}
