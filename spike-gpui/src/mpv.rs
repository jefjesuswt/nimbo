// libmpv mínimo por FFI (solo lo que usa el spike) + render por software a
// un búfer BGRA que GPUI pinta como imagen. ponytail: el render SW es lento
// en 1080p; la versión de verdad iría por el render GL/dmabuf de mpv.
use std::ffi::{CString, c_char, c_int, c_void};
use std::sync::{Arc, Condvar, Mutex};

#[repr(C)]
struct RenderParam {
    kind: c_int,
    data: *mut c_void,
}

const PARAM_INVALID: c_int = 0;
const PARAM_API_TYPE: c_int = 1;
const PARAM_SW_SIZE: c_int = 17;
const PARAM_SW_FORMAT: c_int = 18;
const PARAM_SW_STRIDE: c_int = 19;
const PARAM_SW_POINTER: c_int = 20;
const UPDATE_FRAME: u64 = 1;
const FORMAT_DOUBLE: c_int = 5;
const FORMAT_FLAG: c_int = 3;
const FORMAT_INT64: c_int = 4;

#[link(name = "mpv")]
unsafe extern "C" {
    fn mpv_create() -> *mut c_void;
    fn mpv_initialize(ctx: *mut c_void) -> c_int;
    fn mpv_set_option_string(ctx: *mut c_void, name: *const c_char, data: *const c_char) -> c_int;
    fn mpv_command(ctx: *mut c_void, args: *mut *const c_char) -> c_int;
    fn mpv_get_property(ctx: *mut c_void, name: *const c_char, format: c_int, data: *mut c_void) -> c_int;
    fn mpv_set_property_string(ctx: *mut c_void, name: *const c_char, data: *const c_char) -> c_int;
    fn mpv_set_property(ctx: *mut c_void, name: *const c_char, format: c_int, data: *mut c_void) -> c_int;
    fn mpv_terminate_destroy(ctx: *mut c_void);
    fn mpv_render_context_create(res: *mut *mut c_void, mpv: *mut c_void, params: *mut RenderParam) -> c_int;
    fn mpv_render_context_set_update_callback(ctx: *mut c_void, cb: Option<extern "C" fn(*mut c_void)>, data: *mut c_void);
    fn mpv_render_context_update(ctx: *mut c_void) -> u64;
    fn mpv_render_context_render(ctx: *mut c_void, params: *mut RenderParam) -> c_int;
    fn mpv_render_context_free(ctx: *mut c_void);
}

// Un fotograma listo para GPUI (BGRA, alfa a 255).
pub struct Frame {
    pub width: u32,
    pub height: u32,
    pub bgra: Vec<u8>,
}

struct Handles {
    mpv: *mut c_void,
    render: *mut c_void,
}
unsafe impl Send for Handles {}
unsafe impl Sync for Handles {}

// Aviso de "hay fotograma nuevo" desde el hilo de mpv al de render.
#[derive(Default)]
struct Wake {
    dirty: Mutex<bool>,
    cv: Condvar,
}

extern "C" fn on_update(data: *mut c_void) {
    let w = unsafe { &*(data as *const Wake) };
    *w.dirty.lock().unwrap() = true;
    w.cv.notify_one();
}

pub struct Mpv {
    h: Arc<Handles>,
    wake: Arc<Wake>,
    alive: Arc<Mutex<bool>>,
    thread: Option<std::thread::JoinHandle<()>>,
}

impl Mpv {
    // frames: cada fotograma nuevo, a lo sumo max_w de ancho.
    pub fn new(frames: async_channel::Sender<Frame>, max_w: u32) -> Option<Self> {
        unsafe {
            let mpv = mpv_create();
            if mpv.is_null() {
                return None;
            }
            for (k, v) in [
                ("vo", "libmpv"),
                ("terminal", "no"),
                ("input-default-bindings", "no"),
                ("keep-open", "yes"),
                ("hwdec", "no"),
                ("cache", "yes"),
            ] {
                let (k, v) = (CString::new(k).unwrap(), CString::new(v).unwrap());
                mpv_set_option_string(mpv, k.as_ptr(), v.as_ptr());
            }
            if mpv_initialize(mpv) < 0 {
                return None;
            }
            let sw = CString::new("sw").unwrap();
            let mut params = [
                RenderParam { kind: PARAM_API_TYPE, data: sw.as_ptr() as *mut c_void },
                RenderParam { kind: PARAM_INVALID, data: std::ptr::null_mut() },
            ];
            let mut render = std::ptr::null_mut();
            if mpv_render_context_create(&mut render, mpv, params.as_mut_ptr()) < 0 {
                mpv_terminate_destroy(mpv);
                return None;
            }
            let wake = Arc::new(Wake::default());
            mpv_render_context_set_update_callback(render, Some(on_update), Arc::as_ptr(&wake) as *mut c_void);
            let mut me = Self { h: Arc::new(Handles { mpv, render }), wake, alive: Arc::new(Mutex::new(true)), thread: None };
            me.thread = Some(me.spawn_render(frames, max_w));
            Some(me)
        }
    }

    fn spawn_render(&self, frames: async_channel::Sender<Frame>, max_w: u32) -> std::thread::JoinHandle<()> {
        let (h, wake, alive) = (self.h.clone(), self.wake.clone(), self.alive.clone());
        std::thread::spawn(move || {
            let fmt = CString::new("bgr0").unwrap();
            loop {
                {
                    let mut d = wake.dirty.lock().unwrap();
                    while !*d {
                        d = wake.cv.wait(d).unwrap();
                    }
                    *d = false;
                }
                if !*alive.lock().unwrap() {
                    break;
                }
                let flags = unsafe { mpv_render_context_update(h.render) };
                if flags & UPDATE_FRAME == 0 {
                    continue;
                }
                let (vw, vh) = (prop_i64(h.mpv, "dwidth"), prop_i64(h.mpv, "dheight"));
                if vw <= 0 || vh <= 0 {
                    continue;
                }
                // Tamaño de render: el del vídeo, como mucho max_w de ancho.
                let w = (vw as u32).min(max_w);
                let hgt = ((vh as f64) * (w as f64) / (vw as f64)).round() as u32;
                let stride = (w as usize) * 4;
                let mut buf = vec![0u8; stride * hgt as usize];
                let mut size = [w as c_int, hgt as c_int];
                let mut stride_v = stride;
                let mut params = [
                    RenderParam { kind: PARAM_SW_SIZE, data: size.as_mut_ptr() as *mut c_void },
                    RenderParam { kind: PARAM_SW_FORMAT, data: fmt.as_ptr() as *mut c_void },
                    RenderParam { kind: PARAM_SW_STRIDE, data: &mut stride_v as *mut usize as *mut c_void },
                    RenderParam { kind: PARAM_SW_POINTER, data: buf.as_mut_ptr() as *mut c_void },
                    RenderParam { kind: PARAM_INVALID, data: std::ptr::null_mut() },
                ];
                unsafe { mpv_render_context_render(h.render, params.as_mut_ptr()) };
                for px in buf.chunks_exact_mut(4) {
                    px[3] = 255;
                }
                // Si la UI va atrasada, se pierde este fotograma (no se encola).
                if frames.is_empty() {
                    let _ = frames.try_send(Frame { width: w, height: hgt, bgra: buf });
                }
            }
        })
    }

    pub fn command(&self, args: &[&str]) {
        let owned: Vec<CString> = args.iter().map(|a| CString::new(*a).unwrap()).collect();
        let mut ptrs: Vec<*const c_char> = owned.iter().map(|c| c.as_ptr()).collect();
        ptrs.push(std::ptr::null());
        unsafe { mpv_command(self.h.mpv, ptrs.as_mut_ptr()) };
    }

    pub fn load(&self, url: &str) {
        self.command(&["loadfile", url]);
        self.set_pause(false);
    }

    pub fn seek(&self, secs: f64) {
        self.command(&["seek", &format!("{secs:.2}"), "absolute"]);
    }

    pub fn set_pause(&self, on: bool) {
        let name = CString::new("pause").unwrap();
        let mut v: c_int = on as c_int;
        unsafe { mpv_set_property(self.h.mpv, name.as_ptr(), FORMAT_FLAG, &mut v as *mut c_int as *mut c_void) };
    }

    pub fn paused(&self) -> bool {
        let name = CString::new("pause").unwrap();
        let mut v: c_int = 0;
        unsafe { mpv_get_property(self.h.mpv, name.as_ptr(), FORMAT_FLAG, &mut v as *mut c_int as *mut c_void) };
        v != 0
    }

    pub fn position(&self) -> f64 {
        prop_f64(self.h.mpv, "time-pos")
    }

    pub fn duration(&self) -> f64 {
        prop_f64(self.h.mpv, "duration")
    }

    pub fn set_string(&self, name: &str, v: &str) {
        let (n, v) = (CString::new(name).unwrap(), CString::new(v).unwrap());
        unsafe { mpv_set_property_string(self.h.mpv, n.as_ptr(), v.as_ptr()) };
    }

    pub fn set_f64(&self, name: &str, mut v: f64) {
        let n = CString::new(name).unwrap();
        unsafe { mpv_set_property(self.h.mpv, n.as_ptr(), FORMAT_DOUBLE, &mut v as *mut f64 as *mut c_void) };
    }

    pub fn eof(&self) -> bool {
        let name = CString::new("eof-reached").unwrap();
        let mut v: c_int = 0;
        unsafe { mpv_get_property(self.h.mpv, name.as_ptr(), FORMAT_FLAG, &mut v as *mut c_int as *mut c_void) };
        v != 0
    }

    pub fn has_video(&self) -> bool {
        prop_i64(self.h.mpv, "dwidth") > 0
    }

}

fn prop_f64(mpv: *mut c_void, name: &str) -> f64 {
    let n = CString::new(name).unwrap();
    let mut v = 0f64;
    unsafe { mpv_get_property(mpv, n.as_ptr(), FORMAT_DOUBLE, &mut v as *mut f64 as *mut c_void) };
    v
}

fn prop_i64(mpv: *mut c_void, name: &str) -> i64 {
    let n = CString::new(name).unwrap();
    let mut v = 0i64;
    unsafe { mpv_get_property(mpv, n.as_ptr(), FORMAT_INT64, &mut v as *mut i64 as *mut c_void) };
    v
}

impl Drop for Mpv {
    fn drop(&mut self) {
        *self.alive.lock().unwrap() = false;
        on_update(Arc::as_ptr(&self.wake) as *mut c_void);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
        unsafe {
            mpv_render_context_set_update_callback(self.h.render, None, std::ptr::null_mut());
            mpv_render_context_free(self.h.render);
            mpv_terminate_destroy(self.h.mpv);
        }
    }
}
