// Servidor HTTP en 127.0.0.1 con Range: mpv hace seek pidiendo otro rango y
// TDLib salta la descarga a ese punto. Igual que Stream.kt del spike Compose.
use std::io::Read;
use std::sync::Arc;
use std::sync::atomic::AtomicU64;
use tiny_http::{Header, Response, Server, StatusCode};

const CHUNK: i64 = 512 * 1024;

struct TgReader {
    rt: tokio::runtime::Handle,
    id: i32,
    pos: i64,
    end: i64,
    buf: Vec<u8>,
    off: usize,
    served: Arc<AtomicU64>,
}

impl Read for TgReader {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        if self.off >= self.buf.len() {
            if self.pos > self.end {
                return Ok(0);
            }
            let want = CHUNK.min(self.end - self.pos + 1);
            let got = self.rt.block_on(crate::tg::read(crate::tg::client(), self.id, self.pos, want));
            let Some(b) = got.filter(|b| !b.is_empty()) else { return Ok(0) };
            self.pos += b.len() as i64;
            crate::tg::count(&self.served, b.len());
            self.buf = b;
            self.off = 0;
        }
        let n = out.len().min(self.buf.len() - self.off);
        out[..n].copy_from_slice(&self.buf[self.off..self.off + n]);
        self.off += n;
        Ok(n)
    }
}

// Devuelve el puerto. Un hilo por petición (mpv abre varias al hacer seek).
pub fn serve(rt: tokio::runtime::Handle, served: Arc<AtomicU64>) -> u16 {
    let server = Arc::new(Server::http("127.0.0.1:0").unwrap());
    let port = server.server_addr().to_ip().unwrap().port();
    std::thread::spawn(move || {
        for req in server.incoming_requests() {
            let (rt, served) = (rt.clone(), served.clone());
            std::thread::spawn(move || {
                let Some(id) = req.url().rsplit('/').next().and_then(|s| s.parse::<i32>().ok()) else { return };
                let size = rt.block_on(crate::tg::size(crate::tg::client(), id));
                let range = req.headers().iter().find(|h| h.field.equiv("Range")).map(|h| h.value.as_str().to_string());
                let (start, end) = match range.as_deref().and_then(|r| r.strip_prefix("bytes=")) {
                    Some(r) => {
                        let (a, b) = r.split_once('-').unwrap_or((r, ""));
                        (a.parse().unwrap_or(0), b.parse().unwrap_or(size - 1).min(size - 1))
                    }
                    None => (0, size - 1),
                };
                let reader = TgReader { rt, id, pos: start, end, buf: vec![], off: 0, served };
                let mut headers = vec![Header::from_bytes("Accept-Ranges", "bytes").unwrap()];
                if range.is_some() {
                    headers.push(Header::from_bytes("Content-Range", format!("bytes {start}-{end}/{size}")).unwrap());
                }
                let code = if range.is_some() { 206 } else { 200 };
                let resp = Response::new(StatusCode(code), headers, reader, Some((end - start + 1) as usize), None);
                let _ = req.respond(resp); // mpv corta al hacer seek: normal
            });
        }
    });
    port
}
