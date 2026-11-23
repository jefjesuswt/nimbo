// Bienvenida y login: tarjeta centrada, QR primero, teléfono como alternativa.
use crate::app::App;
use crate::tg::{Auth, Cmd};
use gpui_kit::component::button::*;
use gpui_kit::component::input::Input;
use gpui_kit::component::*;
use gpui_kit::prelude::FluentBuilder as _;
use gpui_kit::*;

fn icon(name: &str) -> Icon {
    Icon::empty().path(format!("icons/{name}.svg"))
}

// Marca: disco con la nube, título y lema.
fn brand(cx: &App2) -> impl IntoElement {
    let t = cx.theme();
    div()
        .v_flex()
        .items_center()
        .gap_3()
        .child(
            div()
                .size(px(72.))
                .rounded_full()
                .bg(t.foreground)
                .flex()
                .items_center()
                .justify_center()
                .child(icon("cloud").with_size(px(36.)).text_color(t.background)),
        )
        .child(div().text_size(px(28.)).font_weight(FontWeight::SEMIBOLD).child("Nimbo"))
        .child(
            div()
                .text_color(t.muted_foreground)
                .text_center()
                .child("Your personal cloud, stored in Telegram's Saved Messages."),
        )
}

type App2 = gpui_kit::App;

fn card(cx: &App2) -> Div {
    let t = cx.theme();
    div()
        .v_flex()
        .w(px(380.))
        .p_6()
        .gap_4()
        .rounded(px(16.))
        .border_1()
        .border_color(t.border)
        .bg(t.popover)
}

// Pasos numerados, para el QR.
fn steps(cx: &App2, items: &[&str]) -> impl IntoElement {
    let t = cx.theme();
    div().v_flex().gap_2().children(items.iter().enumerate().map(|(i, s)| {
        div()
            .h_flex()
            .gap_3()
            .items_start()
            .child(
                div()
                    .size(px(22.))
                    .flex_none()
                    .rounded_full()
                    .bg(t.secondary)
                    .text_size(px(12.))
                    .flex()
                    .items_center()
                    .justify_center()
                    .child(format!("{}", i + 1)),
            )
            .child(div().text_color(t.muted_foreground).child(s.to_string()))
    }))
}

fn qr(link: &str) -> impl IntoElement {
    // QR como rejilla de divs: sin imagen ni assets.
    let code = qrcode::QrCode::new(link.as_bytes()).unwrap();
    let w = code.width();
    let colors = code.to_colors();
    let cell = px(5.);
    div()
        .bg(gpui_kit::white())
        .p(px(14.))
        .rounded(px(12.))
        .child(div().v_flex().children((0..w).map(|y| {
            div().h_flex().children((0..w).map(|x| {
                let dark = colors[y * w + x] == qrcode::Color::Dark;
                div().size(cell).bg(if dark { gpui_kit::black() } else { gpui_kit::white() })
            }))
        })))
}

impl App {
    pub fn login(&mut self, cx: &mut Context<Self>) -> impl IntoElement {
        let body = match self.auth.clone() {
            Auth::Loading => card(cx).items_center().child(div().text_color(cx.theme().muted_foreground).child("Connecting to Telegram…")),
            Auth::WaitPhone if !self.use_phone => card(cx)
                .child(
                    Button::new("qr")
                        .primary()
                        .large()
                        .w_full()
                        .icon(icon("qr-code"))
                        .label("Log in with QR code")
                        .on_click(cx.listener(|this, _, _, _| {
                            let _ = this.cmd.send(Cmd::Qr);
                        })),
                )
                .child(
                    Button::new("phone")
                        .ghost()
                        .w_full()
                        .icon(icon("smartphone"))
                        .label("Use phone number instead")
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.use_phone = true;
                            cx.notify();
                        })),
                ),
            Auth::WaitPhone => self.field_card("Phone number", "With country code, e.g. +1 555 123 4567", false, Cmd::Phone, cx),
            Auth::WaitQr(link) => card(cx)
                .items_center()
                .child(div().font_weight(FontWeight::SEMIBOLD).child("Scan with your phone"))
                .child(qr(&link))
                .child(steps(cx, &["Open Telegram on your phone", "Go to Settings › Devices", "Tap Link Desktop Device and scan this code"])),
            Auth::WaitCode => self.field_card("Login code", "Telegram sent it to your other devices", false, Cmd::Code, cx),
            Auth::WaitPassword(hint) => {
                let sub = if hint.is_empty() { "Your account has two-step verification".to_string() } else { format!("Hint: {hint}") };
                self.field_card("Password", &sub, true, Cmd::Password, cx)
            }
            Auth::NeedKeys(cli) => self.keys_card(cli, cx),
            Auth::Ready => card(cx),
        };
        let error = self.error.clone();
        div()
            .size_full()
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .v_flex()
                    .items_center()
                    .gap_6()
                    .child(brand(cx))
                    .child(body)
                    .when_some(error, |d, e| d.child(div().text_color(cx.theme().danger).child(e))),
            )
    }

    // Primer arranque: api_id y api_hash propios (o los de nimbo-cli).
    fn keys_card(&self, cli: bool, cx: &mut Context<Self>) -> Div {
        let t = cx.theme().clone();
        card(cx)
            .child(
                div()
                    .v_flex()
                    .gap_1()
                    .child(div().font_weight(FontWeight::SEMIBOLD).child("Telegram API keys"))
                    .child(div().text_size(px(13.)).text_color(t.muted_foreground)
                        .child("Create them at my.telegram.org › API development tools. They're kept in your system keyring.")),
            )
            .child(div().v_flex().gap_1().child(div().text_size(px(12.)).child("api_id")).child(Input::new(&self.field)))
            .child(div().v_flex().gap_1().child(div().text_size(px(12.)).child("api_hash")).child(Input::new(&self.secret)))
            .child(
                Button::new("keys-ok").primary().large().w_full().label("Continue")
                    .on_click(cx.listener(|this, _, w, cx| {
                        let id = this.field.read(cx).value().trim().parse::<i32>();
                        let hash = this.secret.read(cx).value().trim().to_string();
                        match id {
                            Ok(id) if id > 0 && hash.len() >= 16 => {
                                let _ = this.cmd.send(Cmd::Keys(id, hash));
                                // Los mismos campos sirven luego para teléfono y contraseña.
                                this.field.update(cx, |s, cx| s.set_value("", w, cx));
                                this.secret.update(cx, |s, cx| s.set_value("", w, cx));
                                this.error = None;
                            }
                            _ => this.error = Some("Check the api_id (a number) and the api_hash".into()),
                        }
                        cx.notify();
                    })),
            )
            .when(cli, |d| {
                d.child(Button::new("keys-cli").ghost().w_full().icon(icon("terminal")).label("Use the keys from nimbo-cli")
                    .on_click(cx.listener(|this, _, _, _| { let _ = this.cmd.send(Cmd::ImportCliKeys); })))
            })
    }

    fn field_card(&self, title: &str, sub: &str, secret: bool, make: fn(String) -> Cmd, cx: &mut Context<Self>) -> Div {
        let st = if secret { self.secret.clone() } else { self.field.clone() };
        card(cx)
            .child(
                div()
                    .v_flex()
                    .gap_1()
                    .child(div().font_weight(FontWeight::SEMIBOLD).child(title.to_string()))
                    .child(div().text_size(px(13.)).text_color(cx.theme().muted_foreground).child(sub.to_string())),
            )
            .child(Input::new(&st).large())
            .child(
                Button::new("send")
                    .primary()
                    .large()
                    .w_full()
                    .label("Continue")
                    .on_click(cx.listener(move |this, _, _, cx| this.send_field(secret, make, cx))),
            )
    }
}
