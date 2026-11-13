//! A UniFFI surface over `pdfcraft-engine` for Android (Kotlin).
//!
//! Every exported body runs under `catch_unwind`; a panic becomes `PdfError::Panic`, so no
//! unwind crosses the FFI boundary. The document sits behind a `Mutex`, so the object can be
//! shared across threads. A panic while the lock is held poisons it; later calls recover the
//! guard, because the state they read is only ever replaced whole.

use std::any::Any;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::sync::{Arc, Mutex, MutexGuard};

use pdfcraft_engine::{Session, render_password_for};
use pdfcraft_render::{
    OpenError, PageRenderer, RenderConfig, RenderRequest, RequestKind, device_pixels,
};

uniffi::setup_scaffolding!();

mod api_types;
pub use api_types::*;
mod files;
mod session;
mod session_ops;
pub use files::{detect_document_corners, images_to_pdf, merge, rectify_and_clean};
pub use session::PdfSession;

/// Inputs above this size are refused before parsing.
pub const MAX_INPUT_BYTES: usize = 256 * 1024 * 1024;
/// Renders above this many device pixels are refused before rasterising.
pub const MAX_RENDER_PIXELS: u64 = 64 * 1000 * 1000;

#[derive(Debug, Clone, PartialEq, uniffi::Error)]
pub enum PdfError {
    /// The bytes are not a PDF this engine can read (or the feature is unsupported).
    InvalidPdf { detail: String },
    /// The document is encrypted and no password was given.
    PasswordRequired,
    /// The given password does not open the document.
    WrongPassword,
    /// Input or output exceeds a guard-rail limit.
    TooLarge { detail: String },
    /// A caller argument is out of range (page index, scale).
    InvalidArgument { detail: String },
    /// The engine failed to render a page.
    Render { detail: String },
    /// A panic was caught inside the Rust side.
    Panic { detail: String },
    /// The operation is not available in this build (feature gated off, or the engine lacks it).
    Unsupported { detail: String },
    /// Reading or writing bytes failed.
    Io { detail: String },
    /// A cooperative cancel (optimize, scan clean) was honoured.
    Cancelled,
    /// The document's permissions or the caller's credentials do not allow this operation
    /// (e.g. removing protection without the owner password, copying text when copying is forbidden).
    NotPermitted { detail: String },
}

impl std::fmt::Display for PdfError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidPdf { detail } => write!(f, "invalid PDF: {detail}"),
            Self::PasswordRequired => write!(f, "password required"),
            Self::WrongPassword => write!(f, "wrong password"),
            Self::TooLarge { detail } => write!(f, "too large: {detail}"),
            Self::InvalidArgument { detail } => write!(f, "invalid argument: {detail}"),
            Self::Render { detail } => write!(f, "render failed: {detail}"),
            Self::Panic { detail } => write!(f, "internal panic: {detail}"),
            Self::Unsupported { detail } => write!(f, "unsupported: {detail}"),
            Self::Io { detail } => write!(f, "i/o error: {detail}"),
            Self::Cancelled => write!(f, "cancelled"),
            Self::NotPermitted { detail } => write!(f, "not permitted: {detail}"),
        }
    }
}

impl std::error::Error for PdfError {}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record)]
pub struct PageSize {
    pub width_pt: f32,
    pub height_pt: f32,
}

#[derive(Debug, Clone, PartialEq, uniffi::Record)]
pub struct RenderedImage {
    pub width: u32,
    pub height: u32,
    /// Premultiplied RGBA8, row-major, `width * height * 4` bytes.
    pub rgba: Vec<u8>,
}

/// State behind the mutex: the renderer owns the parsed document, `pages` the displayed sizes.
struct Inner {
    renderer: PageRenderer,
    pages: Vec<PageSize>,
}

#[derive(uniffi::Object)]
pub struct PdfDoc {
    inner: Mutex<Inner>,
}

pub(crate) fn panic_message(payload: &(dyn Any + Send)) -> String {
    if let Some(s) = payload.downcast_ref::<&str>() {
        (*s).to_owned()
    } else if let Some(s) = payload.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic payload".to_owned()
    }
}

/// Run `f`, turning a panic into `PdfError::Panic`.
pub(crate) fn guard<T>(f: impl FnOnce() -> Result<T, PdfError>) -> Result<T, PdfError> {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(result) => result,
        Err(payload) => Err(PdfError::Panic {
            detail: panic_message(payload.as_ref()),
        }),
    }
}

pub(crate) fn map_open_error(e: OpenError) -> PdfError {
    match e {
        OpenError::Invalid(detail) | OpenError::Unsupported(detail) => {
            PdfError::InvalidPdf { detail }
        }
        OpenError::NeedsPassword => PdfError::PasswordRequired,
        OpenError::WrongPassword => PdfError::WrongPassword,
    }
}

fn lock(m: &Mutex<Inner>) -> MutexGuard<'_, Inner> {
    m.lock().unwrap_or_else(|poisoned| poisoned.into_inner())
}

#[uniffi::export]
impl PdfDoc {
    /// Open a PDF from memory. `password` is the user or owner password, if any.
    #[uniffi::constructor]
    pub fn open(bytes: Vec<u8>, password: Option<String>) -> Result<Arc<Self>, PdfError> {
        guard(|| {
            if bytes.len() > MAX_INPUT_BYTES {
                return Err(PdfError::TooLarge {
                    detail: format!("input is {} bytes, limit is {MAX_INPUT_BYTES}", bytes.len()),
                });
            }
            let bytes = Arc::new(bytes);
            let mut session = Session::new();
            let id = session
                .open("input.pdf", None, bytes, password.as_deref())
                .map_err(map_open_error)?;
            let doc = session.get(id).ok_or_else(|| PdfError::InvalidPdf {
                detail: "document was not registered".into(),
            })?;
            let pages = doc
                .info
                .pages
                .iter()
                .map(|p| PageSize {
                    width_pt: p.width,
                    height_pt: p.height,
                })
                .collect();
            // The session's copy is what the viewer draws (`display`); the renderer takes the same
            // bytes with the password it authenticates with.
            let config = RenderConfig {
                password: render_password_for(doc.display.clone(), password.as_deref())
                    .map(Arc::from),
                ..Default::default()
            };
            let renderer = PageRenderer::new(doc.display.clone(), config);
            Ok(Arc::new(Self {
                inner: Mutex::new(Inner { renderer, pages }),
            }))
        })
    }

    /// Number of pages in the document.
    pub fn page_count(&self) -> Result<u32, PdfError> {
        guard(|| Ok(lock(&self.inner).pages.len() as u32))
    }

    /// Displayed size of page `page` (0-based), in PDF points, after rotation.
    pub fn page_size(&self, page: u32) -> Result<PageSize, PdfError> {
        guard(|| {
            let inner = lock(&self.inner);
            inner
                .pages
                .get(page as usize)
                .copied()
                .ok_or_else(|| PdfError::InvalidArgument {
                    detail: format!(
                        "page {page} out of range (document has {} pages)",
                        inner.pages.len()
                    ),
                })
        })
    }

    /// Rasterise page `page` (0-based) at `scale` device pixels per point.
    pub fn render_page(&self, page: u32, scale: f32) -> Result<RenderedImage, PdfError> {
        guard(|| {
            if !(scale.is_finite() && scale > 0.0) {
                return Err(PdfError::InvalidArgument {
                    detail: format!("scale must be a positive number, got {scale}"),
                });
            }
            let mut inner = lock(&self.inner);
            let size = inner.pages.get(page as usize).copied().ok_or_else(|| {
                PdfError::InvalidArgument {
                    detail: format!(
                        "page {page} out of range (document has {} pages)",
                        inner.pages.len()
                    ),
                }
            })?;
            let w = u64::from(device_pixels(size.width_pt, scale));
            let h = u64::from(device_pixels(size.height_pt, scale));
            if w.saturating_mul(h) > MAX_RENDER_PIXELS {
                return Err(PdfError::TooLarge {
                    detail: format!("render is {w}x{h}, limit is {MAX_RENDER_PIXELS} pixels"),
                });
            }
            let out = inner.renderer.render(RenderRequest {
                page: page as usize,
                kind: RequestKind::Pixels,
                scale,
                ..Default::default()
            });
            if let Some(detail) = out.error {
                return Err(PdfError::Render { detail });
            }
            Ok(RenderedImage {
                width: out.width,
                height: out.height,
                rgba: out.rgba.to_vec(),
            })
        })
    }
}

/// Deliberately panic inside the Rust side, to test that the panic surfaces as `PdfError::Panic`.
#[uniffi::export]
pub fn debug_force_panic() -> Result<(), PdfError> {
    guard(|| -> Result<(), PdfError> { panic!("debug_force_panic requested") })
}
