//! Hostile-input gate. Every case goes through the full pipeline:
//! `open`, `meta`, `page_infos`, `render_tile`, `search_page`, `select_text`, `outline`, `links`,
//! `rotate_pages`, `undo`, `save_full`. Each call may return `Err`; none may return
//! `PdfError::Panic`, unwind out of the case, hang, abort, or use more than 1 GiB.
//!
//! Two runners:
//! - thread runner: the case runs on its own thread with a 10 s `recv_timeout`. A timed-out thread
//!   is reported as HANG and leaked. Used for byte-mutation families and for structures the engine
//!   handles without deep recursion.
//! - child runner: the case runs in a child process (this test binary, `hostile_child`), so an
//!   abort (stack overflow, allocation failure) is reported as CRASH and does not kill the gate.
//!   Used for deep nesting, huge allocations and the 1 GiB inflate. The child also reports peak RSS.
//!
//! Deterministic: every generator is seeded xorshift; failure names include the seed.
//! Run: `cargo test -p pdfa-ffi --test hostile -- --nocapture`.

#![allow(clippy::should_implement_trait)]
#![allow(clippy::type_complexity)] // test code: readability over the lint

use std::collections::{BTreeMap, VecDeque};
use std::fs;
use std::io::Read;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use pdfa_ffi::{PdfError, PdfSession, Pt, Rotation};

/// Per-case wall-clock limit (thread and child runners). Shared CI runners are slow and run these
/// in a debug build next to other jobs, so the limit is looser there; a real hang still trips it.
fn case_limit() -> Duration {
    Duration::from_secs(if std::env::var_os("CI").is_some() {
        40
    } else {
        10
    })
}
/// Peak RSS limit for a child case, in KiB.
const RSS_LIMIT_KB: u64 = 1024 * 1024;
/// Concurrent cases.
const WORKERS: usize = 6;
const RESULT_MARK: &str = "HOSTILE_RESULT ";
const SEED_FLIP: u64 = 0x5EED_0001;
const SEED_ZERO: u64 = 0x5EED_0002;
const SEED_GARBAGE: u64 = 0x5EED_0100;

/// Case names that are known findings (see corpus/FINDINGS.md). They are reported as KNOWN and do
/// not fail the gate. Each has an `#[ignore]` regression test below.
const KNOWN: &[&str] = &[
    "nesting/resources/d=500",
    "nesting/resources/d=10000/stack=2MiB",
    "nesting/resources/d=10000/stack=512MiB",
    "nesting/resources/d=100000/stack=2MiB",
    "nesting/resources/d=100000/stack=512MiB",
    "kids/100000-same-page",
    "flate/bomb-1GiB",
    "operators/10MB-text-ops",
];

// ---- pipeline -------------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq)]
enum Outcome {
    /// Every call returned `Ok`.
    Ok,
    /// Some call returned an error other than a panic (the first one is kept).
    Err(String),
    /// Some call returned `PdfError::Panic`. Always a finding.
    Panic(String),
}

fn step<T>(acc: &mut Outcome, what: &str, r: Result<T, PdfError>) {
    match r {
        Ok(_) => {}
        Err(PdfError::Panic { detail }) => *acc = Outcome::Panic(format!("{what}: {detail}")),
        Err(e) => {
            if *acc == Outcome::Ok {
                *acc = Outcome::Err(format!("{what}: {e}"));
            }
        }
    }
}

/// With `HOSTILE_TRACE` set, every call is announced on stderr before it runs, so a stack overflow
/// or abort can be attributed to the call that caused it.
fn trace(what: &str) {
    if std::env::var_os("HOSTILE_TRACE").is_some() {
        eprintln!("TRACE {what}");
    }
}

fn pipeline(bytes: &[u8]) -> Outcome {
    let mut acc = Outcome::Ok;
    trace("open");
    let session = match PdfSession::open(bytes.to_vec(), None) {
        Ok(s) => s,
        Err(e) => {
            step::<()>(&mut acc, "open", Err(e));
            return acc;
        }
    };
    trace("meta");
    step(&mut acc, "meta", session.meta());
    trace("page_infos");
    step(&mut acc, "page_infos", session.page_infos());
    trace("render_tile");
    step(
        &mut acc,
        "render_tile",
        session.render_tile(0, 1.0, 0, 0, 256, 256, false),
    );
    trace("search_page");
    step(
        &mut acc,
        "search_page",
        session.search_page(0, "a".into(), false, false),
    );
    trace("select_text");
    step(
        &mut acc,
        "select_text",
        session.select_text(0, Pt { x: 0.0, y: 0.0 }, Pt { x: 200.0, y: 200.0 }),
    );
    trace("outline");
    step(&mut acc, "outline", session.outline(0, 50));
    trace("links");
    step(&mut acc, "links", session.links(0));
    trace("rotate_pages");
    step(
        &mut acc,
        "rotate_pages",
        session.rotate_pages(vec![0], Rotation::Cw90),
    );
    trace("undo");
    step(&mut acc, "undo", session.undo());
    trace("save_full");
    step(&mut acc, "save_full", session.save_full());
    acc
}

// ---- cases and verdicts ---------------------------------------------------------------------

#[derive(Clone, Copy, PartialEq)]
enum Mode {
    Thread,
    Child { stack_mb: usize },
}

struct Case {
    family: &'static str,
    name: String,
    bytes: Vec<u8>,
    mode: Mode,
}

#[derive(Debug, Clone)]
enum Verdict {
    Ok,
    Err(String),
    Panic(String),
    Hang,
    Crash(String),
    /// Child peak RSS above `RSS_LIMIT_KB`.
    Mem(u64),
}

impl Verdict {
    fn failing(&self) -> bool {
        !matches!(self, Verdict::Ok | Verdict::Err(_))
    }
    fn detail(&self) -> String {
        match self {
            Verdict::Ok => String::new(),
            Verdict::Err(s) | Verdict::Panic(s) | Verdict::Crash(s) => s.clone(),
            Verdict::Hang => "no result within 10 s".to_owned(),
            Verdict::Mem(kb) => format!("peak rss {kb} KiB over the limit"),
        }
    }
}

struct Rec {
    family: &'static str,
    name: String,
    verdict: Verdict,
    ms: u128,
    rss_kb: u64,
    bytes: Vec<u8>,
}

fn sanitize(name: &str) -> String {
    name.chars()
        .map(|c| {
            if c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.' {
                c
            } else {
                '_'
            }
        })
        .collect()
}

fn one_line(s: &str) -> String {
    s.replace(['\n', '\r'], " ")
}

fn run_thread(bytes: Vec<u8>) -> (Verdict, u128) {
    let (tx, rx) = mpsc::channel();
    thread::spawn(move || {
        let t = Instant::now();
        let r = catch_unwind(AssertUnwindSafe(|| pipeline(&bytes)));
        let _ = tx.send((r, t.elapsed().as_millis()));
    });
    match rx.recv_timeout(case_limit()) {
        Ok((Ok(Outcome::Ok), ms)) => (Verdict::Ok, ms),
        Ok((Ok(Outcome::Err(e)), ms)) => (Verdict::Err(e), ms),
        Ok((Ok(Outcome::Panic(p)), ms)) => (Verdict::Panic(p), ms),
        Ok((Err(_), ms)) => (
            Verdict::Panic("unguarded panic on the case thread".into()),
            ms,
        ),
        Err(RecvTimeoutError::Timeout) => (Verdict::Hang, case_limit().as_millis()),
        Err(RecvTimeoutError::Disconnected) => (
            Verdict::Panic("case thread died without a report".into()),
            0,
        ),
    }
}

fn run_child(c: &Case, stack_mb: usize) -> (Verdict, u128, u64) {
    let dir = PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join("hostile");
    if let Err(e) = fs::create_dir_all(&dir) {
        return (
            Verdict::Crash(format!("cannot create {}: {e}", dir.display())),
            0,
            0,
        );
    }
    let path = dir.join(format!("{}.pdf", sanitize(&c.name)));
    if let Err(e) = fs::write(&path, &c.bytes) {
        return (Verdict::Crash(format!("cannot write case: {e}")), 0, 0);
    }
    let exe = match std::env::current_exe() {
        Ok(p) => p,
        Err(e) => return (Verdict::Crash(format!("no test binary path: {e}")), 0, 0),
    };
    let mut child = match Command::new(exe)
        .args([
            "--exact",
            "hostile_child",
            "--nocapture",
            "--test-threads=1",
        ])
        .env("HOSTILE_CASE", &c.name)
        .env("HOSTILE_INPUT", &path)
        .env("HOSTILE_STACK_MB", stack_mb.to_string())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
    {
        Ok(ch) => ch,
        Err(e) => return (Verdict::Crash(format!("spawn failed: {e}")), 0, 0),
    };
    let start = Instant::now();
    let status = loop {
        match child.try_wait() {
            Ok(Some(st)) => break Some(st),
            Ok(None) if start.elapsed() >= case_limit() => {
                let _ = child.kill();
                let _ = child.wait();
                break None;
            }
            Ok(None) => thread::sleep(Duration::from_millis(5)),
            Err(e) => {
                let _ = child.kill();
                return (Verdict::Crash(format!("wait failed: {e}")), 0, 0);
            }
        }
    };
    let Some(status) = status else {
        return (Verdict::Hang, case_limit().as_millis(), 0);
    };
    let mut out = String::new();
    let mut err = String::new();
    if let Some(mut s) = child.stdout.take() {
        let _ = s.read_to_string(&mut out);
    }
    if let Some(mut s) = child.stderr.take() {
        let _ = s.read_to_string(&mut err);
    }
    // libtest prints "test hostile_child ... " without a newline before the test's own output, so
    // the marker is searched for anywhere on the line.
    let line = out.lines().find_map(|l| {
        l.find(RESULT_MARK)
            .map(|i| l[i + RESULT_MARK.len()..].to_owned())
    });
    match line {
        Some(line) => {
            let mut it = line.splitn(4, ' ');
            let st = it.next().unwrap_or("");
            let ms = it.next().unwrap_or("0").parse::<u128>().unwrap_or(0);
            let rss = it.next().unwrap_or("0").parse::<u64>().unwrap_or(0);
            let detail = it.next().unwrap_or("").to_owned();
            let v = match st {
                "ok" => Verdict::Ok,
                "err" => Verdict::Err(detail),
                "panic" => Verdict::Panic(detail),
                _ => Verdict::Crash(format!("unreadable result line: {line}")),
            };
            (v, ms, rss)
        }
        None => {
            let tail: String = err
                .chars()
                .rev()
                .take(300)
                .collect::<Vec<_>>()
                .into_iter()
                .rev()
                .collect();
            let signal = std::os::unix::process::ExitStatusExt::signal(&status);
            (
                Verdict::Crash(format!(
                    "exit {:?}, signal {:?}, stderr tail: {}",
                    status.code(),
                    signal,
                    one_line(&tail)
                )),
                0,
                0,
            )
        }
    }
}

fn run_one(c: Case) -> Rec {
    let (verdict, ms, rss) = match c.mode {
        Mode::Thread => {
            let (v, ms) = run_thread(c.bytes.clone());
            (v, ms, 0)
        }
        Mode::Child { stack_mb } => run_child(&c, stack_mb),
    };
    let verdict = match verdict {
        Verdict::Ok | Verdict::Err(_) if rss > RSS_LIMIT_KB => Verdict::Mem(rss),
        v => v,
    };
    Rec {
        family: c.family,
        name: c.name,
        verdict,
        ms,
        rss_kb: rss,
        bytes: c.bytes,
    }
}

fn run_all(cases: Vec<Case>) -> Vec<Rec> {
    let queue = Arc::new(Mutex::new(VecDeque::from(cases)));
    let out = Arc::new(Mutex::new(Vec::new()));
    let workers: Vec<_> = (0..WORKERS)
        .map(|_| {
            let q = Arc::clone(&queue);
            let o = Arc::clone(&out);
            thread::spawn(move || {
                loop {
                    let next = q.lock().unwrap_or_else(|p| p.into_inner()).pop_front();
                    let Some(c) = next else { break };
                    eprintln!("START {}", c.name);
                    let rec = run_one(c);
                    o.lock().unwrap_or_else(|p| p.into_inner()).push(rec);
                }
            })
        })
        .collect();
    for w in workers {
        let _ = w.join();
    }
    let mut recs = Arc::try_unwrap(out)
        .ok()
        .expect("workers finished")
        .into_inner()
        .unwrap_or_else(|p| p.into_inner());
    recs.sort_by(|a: &Rec, b: &Rec| a.family.cmp(b.family).then(a.name.cmp(&b.name)));
    recs
}

// ---- memory ---------------------------------------------------------------------------------

#[repr(C)]
struct RUsage {
    raw: [i64; 18],
}

unsafe extern "C" {
    fn getrusage(who: i32, usage: *mut RUsage) -> i32;
}

/// Peak resident set of this process, in KiB. `ru_maxrss` is at index 4 on both macOS (bytes)
/// and Linux (KiB).
fn peak_rss_kb() -> u64 {
    let mut u = RUsage { raw: [0; 18] };
    // SAFETY: `u` is a zeroed buffer of the size of `struct rusage` on these platforms (144 bytes).
    let rc = unsafe { getrusage(0, &mut u) };
    if rc != 0 {
        return 0;
    }
    let v = u.raw[4].max(0) as u64;
    if cfg!(target_os = "macos") {
        v / 1024
    } else {
        v
    }
}

// ---- generators -----------------------------------------------------------------------------

struct Rng(u64);

impl Rng {
    fn new(seed: u64) -> Self {
        Rng(seed | 1)
    }
    fn next(&mut self) -> u64 {
        let mut x = self.0;
        x ^= x << 13;
        x ^= x >> 7;
        x ^= x << 17;
        self.0 = x;
        x
    }
    fn below(&mut self, n: usize) -> usize {
        (self.next() % n.max(1) as u64) as usize
    }
    fn bytes(&mut self, n: usize) -> Vec<u8> {
        (0..n).map(|_| self.next() as u8).collect()
    }
}

/// LSB-first bit writer for DEFLATE.
struct BitWriter {
    out: Vec<u8>,
    acc: u32,
    n: u32,
}

impl BitWriter {
    /// `len` bits of `v`, least significant first (header fields, extra bits).
    fn bits(&mut self, v: u32, len: u32) {
        for i in 0..len {
            self.acc |= ((v >> i) & 1) << self.n;
            self.n += 1;
            if self.n == 8 {
                self.out.push(self.acc as u8);
                self.acc = 0;
                self.n = 0;
            }
        }
    }
    /// A Huffman code, most significant bit first.
    fn code(&mut self, code: u32, len: u32) {
        for i in (0..len).rev() {
            self.bits((code >> i) & 1, 1);
        }
    }
    fn finish(mut self) -> Vec<u8> {
        if self.n > 0 {
            self.out.push(self.acc as u8);
        }
        self.out
    }
}

/// A zlib stream that inflates to `n` zero bytes, using one fixed-Huffman block: a literal 0, then
/// copies of length 258 at distance 1 (13 bits per 258 output bytes). Adler-32 of N zeros is
/// `((N % 65521) << 16) | 1`.
fn zlib_zeros(n: u64) -> Vec<u8> {
    assert!(n >= 1);
    let mut w = BitWriter {
        out: vec![0x78, 0x01],
        acc: 0,
        n: 0,
    };
    w.bits(1, 1); // BFINAL
    w.bits(1, 2); // BTYPE = 01, fixed Huffman
    w.code(0x30, 8); // literal 0
    let rest_total = n - 1;
    for _ in 0..rest_total / 258 {
        w.code(0xC5, 8); // length 258 (symbol 285, no extra bits)
        w.code(0, 5); // distance 1
    }
    for _ in 0..rest_total % 258 {
        w.code(0x30, 8); // literal 0
    }
    w.code(0, 7); // end of block (symbol 256)
    let mut out = w.finish();
    out.extend_from_slice(&((((n % 65521) as u32) << 16) | 1).to_be_bytes());
    out
}

type Obj = (usize, Vec<u8>);

fn o(n: usize, body: &str) -> Obj {
    (n, body.as_bytes().to_vec())
}

fn put(objs: &mut Vec<Obj>, n: usize, body: impl Into<Vec<u8>>) {
    objs.retain(|x| x.0 != n);
    objs.push((n, body.into()));
}

fn raw_stream(len: &str, dict: &str, data: &[u8]) -> Vec<u8> {
    let mut v = format!("<< /Length {len} {dict} >>\nstream\n").into_bytes();
    v.extend_from_slice(data);
    v.extend_from_slice(b"\nendstream");
    v
}

fn stream(dict: &str, data: &[u8]) -> Vec<u8> {
    raw_stream(&data.len().to_string(), dict, data)
}

fn size_of(objs: &[Obj]) -> usize {
    objs.iter().map(|x| x.0).max().map_or(1, |m| m + 1)
}

/// Trailer dictionary with `/Size` taken from the objects, plus `inner`.
fn trailer_with(objs: &[Obj], inner: &str) -> String {
    format!("<< /Size {} {inner} >>", size_of(objs))
}

fn std_entry(_n: usize, real: usize, _xref: usize) -> String {
    format!("{real:010} 00000 n \n")
}

/// Write a PDF from `objs` (any order, any numbers), with an xref table built by `entry`
/// (object number, real offset, xref offset) and an optional `startxref` override.
fn assemble_with(
    mut objs: Vec<Obj>,
    entry: &dyn Fn(usize, usize, usize) -> String,
    startxref: Option<&str>,
    trailer: &str,
) -> Vec<u8> {
    objs.sort_by_key(|x| x.0);
    let mut out = b"%PDF-1.7\n%\xE2\xE3\xCF\xD3\n".to_vec();
    let mut at: Vec<(usize, usize)> = Vec::new();
    for (num, body) in &objs {
        at.push((*num, out.len()));
        out.extend_from_slice(format!("{num} 0 obj\n").as_bytes());
        out.extend_from_slice(body);
        out.extend_from_slice(b"\nendobj\n");
    }
    let xref = out.len();
    let size = size_of(&objs);
    out.extend_from_slice(format!("xref\n0 {size}\n0000000000 65535 f \n").as_bytes());
    for n in 1..size {
        let line = match at.iter().find(|a| a.0 == n) {
            Some(&(_, real)) => entry(n, real, xref),
            None => "0000000000 65535 f \n".to_owned(),
        };
        out.extend_from_slice(line.as_bytes());
    }
    let sx = startxref.map_or_else(|| xref.to_string(), |s| s.to_owned());
    out.extend_from_slice(format!("trailer\n{trailer}\nstartxref\n{sx}\n%%EOF\n").as_bytes());
    out
}

fn assemble(objs: Vec<Obj>) -> Vec<u8> {
    let t = trailer_with(&objs, "/Root 1 0 R");
    assemble_with(objs, &std_entry, None, &t)
}

const HELLO: &[u8] = b"BT /F1 24 Tf 72 700 Td (Hello hostile) Tj ET";
const FONT_RES: &str = "<< /Font << /F1 4 0 R >> >>";

/// Page object 3 with the given MediaBox, resources and extra entries. Contents is object 5.
fn page(mediabox: &str, resources: &str, extra: &str) -> String {
    format!(
        "<< /Type /Page /Parent 2 0 R /MediaBox {mediabox} /Resources {resources} /Contents 5 0 R {extra} >>"
    )
}

/// Base A: one page, one text line, correct xref.
fn a_objs() -> Vec<Obj> {
    vec![
        o(1, "<< /Type /Catalog /Pages 2 0 R >>"),
        o(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
        o(3, &page("[0 0 612 792]", FONT_RES, "")),
        o(
            4,
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>",
        ),
        (5, stream("", HELLO)),
    ]
}

/// Base B: three pages with an outline, two link annotations (one page target, one URI), an
/// /Info dictionary. Mirrors the fixture in tests/session.rs.
fn b_objs() -> Vec<Obj> {
    let font = "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>";
    vec![
        o(1, "<< /Type /Catalog /Pages 2 0 R /Outlines 11 0 R >>"),
        o(2, "<< /Type /Pages /Kids [3 0 R 4 0 R 5 0 R] /Count 3 >>"),
        o(
            3,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 9 0 R >> >> /Contents 6 0 R /Annots [14 0 R 15 0 R] >>",
        ),
        o(
            4,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 500] /Resources << /Font << /F1 9 0 R >> >> /Contents 7 0 R >>",
        ),
        o(
            5,
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 9 0 R >> >> /Contents 8 0 R >>",
        ),
        (6, stream("", b"BT /F1 24 Tf 72 692 Td (Page one) Tj ET")),
        (7, stream("", b"BT /F1 24 Tf 72 400 Td (Page two) Tj ET")),
        (8, stream("", b"BT /F1 24 Tf 72 742 Td (Page three) Tj ET")),
        o(9, font),
        o(
            10,
            "<< /Title (Fixture Title) /Author (Example) /Subject (Tests) /Keywords (a, b) /Producer (hand-made) >>",
        ),
        o(
            11,
            "<< /Type /Outlines /First 12 0 R /Last 13 0 R /Count 2 >>",
        ),
        o(
            12,
            "<< /Title (Chapter 1) /Parent 11 0 R /Next 13 0 R /Dest [3 0 R /Fit] >>",
        ),
        o(
            13,
            "<< /Title (Chapter 3) /Parent 11 0 R /Prev 12 0 R /Dest [5 0 R /Fit] >>",
        ),
        o(
            14,
            "<< /Type /Annot /Subtype /Link /Rect [100 700 200 720] /Border [0 0 0] /Dest [4 0 R /Fit] >>",
        ),
        o(
            15,
            "<< /Type /Annot /Subtype /Link /Rect [100 600 300 620] /Border [0 0 0] /A << /S /URI /URI (https://example.com/) >> >>",
        ),
    ]
}

fn base_a() -> Vec<u8> {
    let objs = a_objs();
    let t = trailer_with(&objs, "/Root 1 0 R");
    assemble_with(objs, &std_entry, None, &t)
}

fn base_b() -> Vec<u8> {
    let objs = b_objs();
    let t = trailer_with(&objs, "/Root 1 0 R /Info 10 0 R");
    assemble_with(objs, &std_entry, None, &t)
}

/// A file whose page content is `content` with dictionary `content_dict` (object 5).
fn content_file(content_dict: &str, content: &[u8]) -> Vec<u8> {
    let mut objs = a_objs();
    put(&mut objs, 5, stream(content_dict, content));
    assemble(objs)
}

#[derive(Default)]
struct Corpus {
    cases: Vec<Case>,
}

impl Corpus {
    /// Byte-mutation families run on the thread runner, as the gate spec asks. Every other family
    /// is hand-built structure, which can abort the process (deep recursion, huge allocation), so
    /// it runs in a child process with a default 2 MiB stack.
    fn t(&mut self, family: &'static str, name: impl Into<String>, bytes: Vec<u8>) {
        let mode = match family {
            "truncation" | "bitflip" | "zerofill" | "garbage" | "header" => Mode::Thread,
            _ => Mode::Child { stack_mb: 2 },
        };
        self.cases.push(Case {
            family,
            name: name.into(),
            bytes,
            mode,
        });
    }
    fn c(
        &mut self,
        family: &'static str,
        name: impl Into<String>,
        bytes: Vec<u8>,
        stack_mb: usize,
    ) {
        self.cases.push(Case {
            family,
            name: name.into(),
            bytes,
            mode: Mode::Child { stack_mb },
        });
    }
}

fn deep_array(d: usize) -> String {
    format!("{}{}", "[".repeat(d), "]".repeat(d))
}

fn deep_dict(d: usize) -> String {
    format!("{}1{}", "<< /K ".repeat(d), " >>".repeat(d))
}

fn corpus() -> Vec<Case> {
    let mut k = Corpus::default();
    let a = base_a();
    let b = base_b();

    // truncation: about 48 cut points per base, plus the last byte removed.
    for (label, base) in [("A", &a), ("B", &b)] {
        let step = (base.len() / 48).max(1);
        for off in (0..base.len()).step_by(step) {
            k.t(
                "truncation",
                format!("truncation/{label}/off={off}"),
                base[..off].to_vec(),
            );
        }
        k.t(
            "truncation",
            format!("truncation/{label}/off=len-1"),
            base[..base.len() - 1].to_vec(),
        );
    }

    // bit flips (100 single bit, 100 multi byte), alternating bases.
    let mut rng = Rng::new(SEED_FLIP);
    for i in 0..200 {
        let (label, base) = if i % 2 == 0 { ("A", &a) } else { ("B", &b) };
        let mut v = base.clone();
        let single = i < 100;
        let n = if single { 1 } else { 2 + i % 7 };
        for _ in 0..n {
            let pos = rng.below(v.len());
            if single {
                v[pos] ^= 1u8 << rng.below(8);
            } else {
                v[pos] = rng.next() as u8;
            }
        }
        k.t(
            "bitflip",
            format!("bitflip/seed={SEED_FLIP:#x}/i={i}/{label}/n={n}"),
            v,
        );
    }

    // zero-filled ranges of 1..=256 bytes.
    let mut rng = Rng::new(SEED_ZERO);
    for i in 0..40 {
        let (label, base) = if i % 2 == 0 { ("A", &a) } else { ("B", &b) };
        let mut v = base.clone();
        let start = rng.below(v.len());
        let len = 1 + rng.below(256usize.min(v.len() - start));
        for x in &mut v[start..start + len] {
            *x = 0;
        }
        k.t(
            "zerofill",
            format!("zerofill/seed={SEED_ZERO:#x}/i={i}/{label}/start={start}/len={len}"),
            v,
        );
    }

    // garbage and header-only inputs.
    for (i, size) in [0usize, 1, 10, 1024, 1 << 20].into_iter().enumerate() {
        let seed = SEED_GARBAGE + i as u64;
        k.t(
            "garbage",
            format!("garbage/seed={seed:#x}/size={size}"),
            Rng::new(seed).bytes(size),
        );
    }
    let mut hdr = b"%PDF-1.7\n".to_vec();
    hdr.extend(Rng::new(SEED_GARBAGE + 10).bytes(1024));
    k.t("garbage", "garbage/pdf-header+1KiB", hdr);
    let mut hdr = b"%PDF-1.7\n".to_vec();
    hdr.extend(Rng::new(SEED_GARBAGE + 11).bytes(1 << 20));
    k.t("garbage", "garbage/pdf-header+1MiB", hdr);
    k.t("garbage", "garbage/zeros-1MiB", vec![0; 1 << 20]);
    k.t("garbage", "garbage/ff-1MiB", vec![0xff; 1 << 20]);
    let heads: Vec<(&str, Vec<u8>)> = vec![
        ("pdf-1.7", b"%PDF-1.7".to_vec()),
        ("pdf-1.7-newline", b"%PDF-1.7\n".to_vec()),
        ("pdf-1.7-eof", b"%PDF-1.7\n%%EOF\n".to_vec()),
        ("pdf-only", b"%PDF-".to_vec()),
        ("xref-only", b"%PDF-1.7\nxref\n0 1\n".to_vec()),
        ("obj-only", b"%PDF-1.7\n1 0 obj\n".to_vec()),
        ("startxref-zero", b"%PDF-1.7\nstartxref\n0\n%%EOF".to_vec()),
    ];
    for (name, bytes) in heads {
        k.t("header", format!("header/{name}"), bytes);
    }

    // xref table and trailer damage on base A.
    let xref_entries: Vec<(&str, Box<dyn Fn(usize, usize, usize) -> String>)> = vec![
        (
            "past-eof",
            Box::new(|_: usize, _: usize, _: usize| "9999999999 00000 n \n".to_owned()),
        ),
        (
            "self-xref-offset",
            Box::new(|_: usize, _: usize, x: usize| format!("{x:010} 00000 n \n")),
        ),
        (
            "negative",
            Box::new(|_: usize, _: usize, _: usize| "-000000001 00000 n \n".to_owned()),
        ),
        (
            "mid-object",
            Box::new(|_: usize, real: usize, _: usize| format!("{:010} 00000 n \n", real + 3)),
        ),
        (
            "all-free",
            Box::new(|_: usize, _: usize, _: usize| "0000000000 65535 f \n".to_owned()),
        ),
        (
            "zero-offsets",
            Box::new(|_: usize, _: usize, _: usize| "0000000000 00000 n \n".to_owned()),
        ),
    ];
    for (name, f) in &xref_entries {
        let objs = a_objs();
        let t = trailer_with(&objs, "/Root 1 0 R");
        k.t(
            "xref",
            format!("xref/entries/{name}"),
            assemble_with(objs, f.as_ref(), None, &t),
        );
    }
    for (name, sx) in [
        ("past-eof", "99999999"),
        ("zero", "0"),
        ("negative", "-1"),
        ("at-object-1", "9"),
    ] {
        let objs = a_objs();
        let t = trailer_with(&objs, "/Root 1 0 R");
        k.t(
            "xref",
            format!("xref/startxref/{name}"),
            assemble_with(objs, &std_entry, Some(sx), &t),
        );
    }
    {
        let objs = a_objs();
        let t = "<< /Size 2147483647 /Root 1 0 R >>".to_owned();
        k.t(
            "xref",
            "xref/size-2^31-1",
            assemble_with(objs, &std_entry, None, &t),
        );
    }

    // missing or wrong /Root.
    for (name, inner) in [
        ("missing", ""),
        ("object-999", "/Root 999 0 R"),
        ("page-object", "/Root 3 0 R"),
        ("stream-object", "/Root 5 0 R"),
        ("font-object", "/Root 4 0 R"),
        ("name-not-ref", "/Root /Catalog"),
    ] {
        let objs = a_objs();
        let t = trailer_with(&objs, inner);
        k.t(
            "root",
            format!("root/{name}"),
            assemble_with(objs, &std_entry, None, &t),
        );
    }
    {
        let mut objs = a_objs();
        put(&mut objs, 1, "<< /Type /Catalog /Pages 1 0 R >>");
        k.t("root", "root/catalog-pages-self", assemble(objs));
    }

    // /Pages loops and /Count.
    {
        let mut v = a_objs();
        put(&mut v, 2, "<< /Type /Pages /Kids [2 0 R] /Count 1 >>");
        k.t("pages-loop", "pages-loop/self-kid", assemble(v));
    }
    {
        let mut v = a_objs();
        put(&mut v, 2, "<< /Type /Pages /Kids [3 0 R 6 0 R] /Count 2 >>");
        put(&mut v, 6, "<< /Type /Pages /Kids [2 0 R] /Count 1 >>");
        k.t("pages-loop", "pages-loop/mutual-2-6", assemble(v));
    }
    {
        let mut v = a_objs();
        put(
            &mut v,
            3,
            "<< /Type /Page /Parent 3 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
        );
        k.t("pages-loop", "pages-loop/page-parent-self", assemble(v));
    }
    {
        let mut v = a_objs();
        put(
            &mut v,
            3,
            "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 612 792] /Contents 5 0 R >>",
        );
        put(&mut v, 2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>");
        k.t("pages-loop", "pages-loop/page-as-pages-self", assemble(v));
    }
    {
        let kids = "2 0 R ".repeat(1000);
        let mut v = a_objs();
        put(
            &mut v,
            2,
            format!("<< /Type /Pages /Kids [{kids}] /Count 1000 >>"),
        );
        k.t("pages-loop", "pages-loop/1000-self-kids", assemble(v));
    }
    for count in [
        "2147483648",
        "-2147483648",
        "-1",
        "1e12",
        "0",
        "99999999999999999999",
        "2147483647",
        "4294967296",
    ] {
        let mut v = a_objs();
        put(
            &mut v,
            2,
            format!("<< /Type /Pages /Kids [3 0 R] /Count {count} >>"),
        );
        k.t("count", format!("count/{count}"), assemble(v));
    }
    {
        // 100000 references to one page, /Count 100000. Child: the engine may walk them on the
        // caller thread.
        let kids = "3 0 R ".repeat(100_000);
        let mut v = a_objs();
        put(
            &mut v,
            2,
            format!("<< /Type /Pages /Kids [{kids}] /Count 100000 >>"),
        );
        k.c("kids", "kids/100000-same-page", assemble(v), 2);
    }

    // deep nesting: placements in MediaBox, Resources and content operands. Shallow depths on the
    // thread runner; deep depths in child processes with a 2 MiB and a 512 MiB stack.
    for d in [50usize, 500] {
        let mut v = a_objs();
        put(&mut v, 3, page(&deep_array(d), FONT_RES, ""));
        k.t("nesting", format!("nesting/mediabox/d={d}"), assemble(v));
        let mut v = a_objs();
        put(&mut v, 3, page("[0 0 612 792]", &deep_dict(d), ""));
        k.t("nesting", format!("nesting/resources/d={d}"), assemble(v));
        k.t(
            "nesting",
            format!("nesting/content/d={d}"),
            content_file("", format!("BT {} TJ ET", deep_array(d)).as_bytes()),
        );
    }
    for d in [10_000usize, 100_000] {
        for stack in [2usize, 512] {
            let mut v = a_objs();
            put(&mut v, 3, page(&deep_array(d), FONT_RES, ""));
            k.c(
                "nesting",
                format!("nesting/mediabox/d={d}/stack={stack}MiB"),
                assemble(v),
                stack,
            );
            let mut v = a_objs();
            put(&mut v, 3, page("[0 0 612 792]", &deep_dict(d), ""));
            k.c(
                "nesting",
                format!("nesting/resources/d={d}/stack={stack}MiB"),
                assemble(v),
                stack,
            );
            let content = format!("BT {} TJ ET", deep_array(d));
            k.c(
                "nesting",
                format!("nesting/content/d={d}/stack={stack}MiB"),
                content_file("", content.as_bytes()),
                stack,
            );
        }
    }

    // /Length wrong or huge.
    for len in ["5", "0", "-1", "999999999", "2147483647", "99 0 R", "1.5"] {
        let mut v = a_objs();
        put(&mut v, 5, raw_stream(len, "", HELLO));
        k.t("length", format!("length/{len}"), assemble(v));
    }

    // Flate: inflation bombs and broken streams.
    let bomb16 = zlib_zeros(16 << 20);
    k.t(
        "flate",
        "flate/bomb-16MiB",
        content_file("/Filter /FlateDecode", &bomb16),
    );
    let bomb1g = zlib_zeros(1 << 30);
    k.c(
        "flate",
        "flate/bomb-1GiB",
        content_file("/Filter /FlateDecode", &bomb1g),
        2,
    );
    let mut bad_adler = zlib_zeros(1000);
    let n = bad_adler.len();
    bad_adler[n - 1] ^= 0xff;
    k.t(
        "flate",
        "flate/bad-adler",
        content_file("/Filter /FlateDecode", &bad_adler),
    );
    let half = zlib_zeros(1 << 20);
    k.t(
        "flate",
        "flate/truncated-half",
        content_file("/Filter /FlateDecode", &half[..half.len() / 2]),
    );
    let mut junk = vec![0x78, 0x9c];
    junk.extend(Rng::new(0x51DE).bytes(100));
    k.t(
        "flate",
        "flate/garbage-after-header",
        content_file("/Filter /FlateDecode", &junk),
    );
    k.t(
        "flate",
        "flate/two-filters",
        content_file("/Filter [/FlateDecode /FlateDecode]", &bomb16[..1000]),
    );
    k.t(
        "flate",
        "flate/unknown-filter",
        content_file("/Filter /LZWDecode", &Rng::new(0x1A2B).bytes(4096)),
    );

    // object streams.
    let objstm = |dict: &str, body: &[u8]| {
        let mut v = a_objs();
        v.retain(|x| x.0 != 1);
        put(&mut v, 7, raw_stream(&body.len().to_string(), dict, body));
        v
    };
    let catalog = b"1 0 << /Type /Catalog /Pages 2 0 R >>";
    k.t(
        "objstm",
        "objstm/control",
        assemble(objstm("/Type /ObjStm /N 1 /First 4", catalog)),
    );
    k.t(
        "objstm",
        "objstm/extends-self",
        assemble(objstm(
            "/Type /ObjStm /N 1 /First 4 /Extends 7 0 R",
            catalog,
        )),
    );
    k.t(
        "objstm",
        "objstm/extends-cycle-to-content",
        assemble(objstm(
            "/Type /ObjStm /N 1 /First 4 /Extends 5 0 R",
            catalog,
        )),
    );
    k.t(
        "objstm",
        "objstm/n-negative",
        assemble(objstm("/Type /ObjStm /N -1 /First -5", catalog)),
    );
    k.t(
        "objstm",
        "objstm/first-past-end",
        assemble(objstm("/Type /ObjStm /N 1 /First 999999", catalog)),
    );
    k.t(
        "objstm",
        "objstm/n-2^31-1",
        assemble(objstm("/Type /ObjStm /N 2147483647 /First 0", catalog)),
    );
    k.t(
        "objstm",
        "objstm/pairs-10000",
        assemble(objstm(
            "/Type /ObjStm /N 10000 /First 40000",
            "1 0 ".repeat(10_000).as_bytes(),
        )),
    );
    {
        let mut v = a_objs();
        v.retain(|x| x.0 != 1);
        put(
            &mut v,
            7,
            raw_stream(
                &catalog.len().to_string(),
                "/Type /ObjStm /N 1 /First 4",
                catalog,
            ),
        );
        let t = trailer_with(&v, "/Root 7 0 R");
        k.t(
            "objstm",
            "objstm/root-is-the-objstm",
            assemble_with(v, &std_entry, None, &t),
        );
    }

    // MediaBox, Rotate and CropBox values; huge render requests.
    let mediaboxes = [
        "[0 0 1000000000 1000000000]",
        "[0 0 NaN NaN]",
        "[0 0 1e308 1e308]",
        "[0 0 0 0]",
        "[0 0 -1e30 1e30]",
        "[1e-30 1e-30 2e-30 2e-30]",
        "[0 0 612]",
        "[(a) (b) 0 0]",
        "[0 0 2147483648 2147483648]",
        "[0 0 99999999999999999999999 1]",
        "[0 0 2147483647 2147483647]",
    ];
    for mb in mediaboxes {
        let mut v = a_objs();
        put(&mut v, 3, page(mb, FONT_RES, ""));
        k.t("mediabox", format!("mediabox/{mb}"), assemble(v));
    }
    {
        let digits = format!("[0 0 {} 1]", "1".repeat(100_000));
        let mut v = a_objs();
        put(&mut v, 3, page(&digits, FONT_RES, ""));
        k.t("mediabox", "mediabox/100k-digit-number", assemble(v));
    }
    for extra in [
        "/Rotate 1000000000",
        "/Rotate -2147483648",
        "/Rotate NaN",
        "/Rotate 1.5e308",
        "/CropBox [0 0 1e9 1e9]",
        "/UserUnit 1e30",
    ] {
        let mut v = a_objs();
        put(&mut v, 3, page("[0 0 612 792]", FONT_RES, extra));
        k.t(
            "mediabox",
            format!("mediabox/page-extra/{extra}"),
            assemble(v),
        );
    }
    k.t(
        "mediabox",
        "mediabox/huge-fill-content",
        content_file("", b"1e9 0 0 1e9 0 0 cm 0 0 1e9 1e9 re f"),
    );
    let mut v = a_objs();
    put(&mut v, 3, page("[0 0 1000000000 1000000000]", FONT_RES, ""));
    k.t("mediabox", "mediabox/1e9-square-tile", assemble(v));

    // encrypted documents with bogus /O /U /Length.
    let enc = [
        (
            "v1-short-o",
            "<< /Filter /Standard /V 1 /R 2 /O (short) /U (x) /P -4 /Length 40 >>",
        ),
        (
            "v5-huge-length",
            "<< /Filter /Standard /V 5 /R 6 /Length 2147483647 /O <00> /U <00> /OE <> /UE <> /Perms <00> /P -1 /CF << /StdCF << /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF >>",
        ),
        (
            "v4-no-cf",
            "<< /Filter /Standard /V 4 /R 4 /O (aaaa) /U () /P 0 >>",
        ),
        (
            "v2-empty-o-u",
            "<< /Filter /Standard /V 2 /R 3 /Length 4096 /O <> /U <> /P 999999999999 >>",
        ),
        (
            "v5-r99",
            "<< /Filter /Standard /V 5 /R 99 /O <0000> /U <0000> /P 0 >>",
        ),
    ];
    for (name, dict) in enc {
        let mut v = a_objs();
        put(&mut v, 6, dict);
        let t = trailer_with(&v, "/Root 1 0 R /Encrypt 6 0 R /ID [<00> <00>]");
        k.t(
            "encrypt",
            format!("encrypt/{name}"),
            assemble_with(v, &std_entry, None, &t),
        );
    }
    {
        let v = a_objs();
        let t = trailer_with(&v, "/Root 1 0 R /Encrypt 99 0 R");
        k.t(
            "encrypt",
            "encrypt/missing-object",
            assemble_with(v, &std_entry, None, &t),
        );
    }
    {
        let mut v = a_objs();
        put(
            &mut v,
            6,
            "<< /Filter /Standard /V 2 /R 3 /Length 40 /O (x) /U (y) /P -1 >>",
        );
        let t = trailer_with(&v, "/Root 1 0 R /Encrypt 1 0 R /ID [<00> <00>]");
        k.t(
            "encrypt",
            "encrypt/points-at-catalog",
            assemble_with(v, &std_entry, None, &t),
        );
    }

    // unbalanced graphics state and operator floods.
    k.t(
        "qops",
        "qops/q-x200k",
        content_file("", "q ".repeat(200_000).as_bytes()),
    );
    k.t(
        "qops",
        "qops/Q-x200k",
        content_file("", "Q ".repeat(200_000).as_bytes()),
    );
    k.t(
        "qops",
        "qops/cm-x100k-unbalanced",
        content_file("", "q 1 0 0 1 0 0 cm ".repeat(100_000).as_bytes()),
    );
    k.t(
        "qops",
        "qops/q-x1M-no-space",
        content_file("", "q".repeat(1_000_000).as_bytes()),
    );
    k.c(
        "operators",
        "operators/10MB-path-ops",
        content_file("", "0 0 m 1 1 l S\n".repeat(714_286).as_bytes()),
        2,
    );
    k.c(
        "operators",
        "operators/10MB-text-ops",
        content_file("", "BT /F1 1 Tf (x) Tj ET\n".repeat(476_191).as_bytes()),
        2,
    );

    // inflate bomb through the thread runner too (16 MiB, above) and the 1 GiB child (above).
    // Flate bombs below the child limit are cheap to name, so the 1 GiB case is the only child.

    k.cases
}

// ---- gate -----------------------------------------------------------------------------------

fn print_table(recs: &[Rec]) {
    let mut rows: BTreeMap<&str, [usize; 7]> = BTreeMap::new();
    for r in recs {
        let row = rows.entry(r.family).or_insert([0; 7]);
        row[0] += 1;
        let idx = match &r.verdict {
            Verdict::Ok => 1,
            Verdict::Err(_) => 2,
            Verdict::Panic(_) => 3,
            Verdict::Hang => 4,
            Verdict::Crash(_) => 5,
            Verdict::Mem(_) => 6,
        };
        row[idx] += 1;
    }
    println!();
    println!("| family | cases | ok | err | panic | hang | crash | mem |");
    println!("|---|---:|---:|---:|---:|---:|---:|---:|");
    let mut total = [0usize; 7];
    for (fam, row) in &rows {
        println!(
            "| {fam} | {} | {} | {} | {} | {} | {} | {} |",
            row[0], row[1], row[2], row[3], row[4], row[5], row[6]
        );
        for i in 0..7 {
            total[i] += row[i];
        }
    }
    println!(
        "| **total** | {} | {} | {} | {} | {} | {} | {} |",
        total[0], total[1], total[2], total[3], total[4], total[5], total[6]
    );
    println!();
}

fn findings_dir() -> PathBuf {
    PathBuf::from(env!("CARGO_TARGET_TMPDIR")).join("hostile-findings")
}

/// Child entry point: only runs when `HOSTILE_CASE` is set by the gate. It runs one case on a
/// thread with the requested stack and prints one `HOSTILE_RESULT` line.
#[test]
fn hostile_child() {
    if std::env::var_os("HOSTILE_CASE").is_none() {
        return;
    }
    let path = std::env::var("HOSTILE_INPUT").expect("HOSTILE_INPUT is set by the gate");
    let stack_mb: usize = std::env::var("HOSTILE_STACK_MB")
        .ok()
        .and_then(|s| s.parse().ok())
        .unwrap_or(2);
    let bytes = fs::read(&path).expect("read case input");
    let start = Instant::now();
    let joined = thread::Builder::new()
        .name("hostile-case".to_owned())
        .stack_size(stack_mb << 20)
        .spawn(move || pipeline(&bytes))
        .expect("spawn case thread")
        .join();
    let ms = start.elapsed().as_millis();
    let (st, detail) = match joined {
        Ok(Outcome::Ok) => ("ok", String::new()),
        Ok(Outcome::Err(e)) => ("err", e),
        Ok(Outcome::Panic(p)) => ("panic", p),
        Err(_) => ("panic", "unguarded panic on the case thread".to_owned()),
    };
    println!(
        "{RESULT_MARK}{st} {ms} {} {}",
        peak_rss_kb(),
        one_line(&detail)
    );
}

/// The gate: runs the whole corpus and fails on any panic, hang, crash or memory overrun that is
/// not listed in `KNOWN`. Failing inputs are written to `CARGO_TARGET_TMPDIR/hostile-findings`.
#[test]
fn hostile_gate() {
    if std::env::var_os("HOSTILE_CASE").is_some() {
        return;
    }
    let cases = corpus();
    let total = cases.len();
    let t0 = Instant::now();
    let recs = run_all(cases);
    let elapsed = t0.elapsed();
    print_table(&recs);

    let mut failures = Vec::new();
    let dir = findings_dir();
    for r in &recs {
        if !r.verdict.failing() {
            continue;
        }
        let known = KNOWN.contains(&r.name.as_str());
        let line = format!(
            "{} [{}] {} ({} ms, rss {} KiB)",
            r.name,
            kind(&r.verdict),
            r.verdict.detail(),
            r.ms,
            r.rss_kb
        );
        if known {
            println!("KNOWN {line}");
            continue;
        }
        let _ = fs::create_dir_all(&dir);
        let path = dir.join(format!("{}.pdf", sanitize(&r.name)));
        let _ = fs::write(&path, &r.bytes);
        println!("FAIL {line}\n     input: {}", path.display());
        failures.push(line);
    }
    println!(
        "{total} cases in {:.1} s; gate process peak RSS {} MiB",
        elapsed.as_secs_f64(),
        peak_rss_kb() / 1024
    );
    if elapsed > Duration::from_secs(60) {
        println!(
            "WARNING: suite took {:.1} s, over the 60 s budget",
            elapsed.as_secs_f64()
        );
    }
    assert!(
        failures.is_empty(),
        "hostile gate: {} failing case(s):\n{}",
        failures.len(),
        failures.join("\n")
    );
}

fn kind(v: &Verdict) -> &'static str {
    match v {
        Verdict::Ok => "ok",
        Verdict::Err(_) => "err",
        Verdict::Panic(_) => "panic",
        Verdict::Hang => "hang",
        Verdict::Crash(_) => "crash",
        Verdict::Mem(_) => "mem",
    }
}

/// Control: the two well-formed bases must pass every call. If they do not, the mutations built
/// from them prove nothing.
#[test]
fn control_base_pdfs_pass_every_call() {
    assert_eq!(pipeline(&base_a()), Outcome::Ok, "base A (one page)");
    assert_eq!(
        pipeline(&base_b()),
        Outcome::Ok,
        "base B (three pages, outline, links)"
    );
}

/// Writes the two well-formed bases as fuzz seeds. Run once with `-- --ignored write_seed_corpus`.
#[test]
#[ignore = "regenerates corpus/seed; run by hand"]
fn write_seed_corpus() {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("corpus/seed");
    fs::create_dir_all(&dir).expect("create corpus/seed");
    fs::write(dir.join("base-a-one-page.pdf"), base_a()).expect("write seed A");
    fs::write(dir.join("base-b-three-pages.pdf"), base_b()).expect("write seed B");
    fs::copy(
        concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/hello.pdf"),
        dir.join("hello.pdf"),
    )
    .expect("copy hello.pdf");
}

// ---- known findings (regression tests, #[ignore]d until fixed; see corpus/FINDINGS.md) --------

fn finding_bytes(file: &str) -> Vec<u8> {
    let path = format!("{}/corpus/findings/{file}", env!("CARGO_MANIFEST_DIR"));
    fs::read(&path).unwrap_or_else(|e| panic!("cannot read {path}: {e}"))
}

/// Bytes of a generated gate case, looked up by name (for inputs too large to commit).
fn generated(name: &str) -> Vec<u8> {
    corpus()
        .into_iter()
        .find(|c| c.name == name)
        .map(|c| c.bytes)
        .unwrap_or_else(|| panic!("no gate case named {name}"))
}

fn child_verdict(bytes: Vec<u8>, name: &str, stack_mb: usize) -> Verdict {
    run_child(
        &Case {
            family: "finding",
            name: name.to_owned(),
            bytes,
            mode: Mode::Child { stack_mb },
        },
        stack_mb,
    )
    .0
}

#[test]
#[ignore = "known finding: nested /Resources at depth 500 overflows a 2 MiB stack and aborts the process"]
fn finding_resources_nesting_500_aborts() {
    let v = child_verdict(
        finding_bytes("nesting-resources-d500.pdf"),
        "finding/resources-d500",
        2,
    );
    assert!(matches!(v, Verdict::Ok | Verdict::Err(_)), "{v:?}");
}

#[test]
#[ignore = "known finding: nested /Resources at depth 10000 overflows a 2 MiB stack and aborts the process"]
fn finding_resources_nesting_10000_aborts() {
    let v = child_verdict(
        finding_bytes("nesting-resources-d10000.pdf"),
        "finding/resources-d10000",
        2,
    );
    assert!(matches!(v, Verdict::Ok | Verdict::Err(_)), "{v:?}");
}

#[test]
#[ignore = "known finding: 100000 /Kids pointing at one page take over 10 s in a debug build"]
fn finding_kids_100000_hang() {
    let v = child_verdict(
        finding_bytes("kids-100000-same-page.pdf"),
        "finding/kids-100000",
        2,
    );
    assert!(matches!(v, Verdict::Ok | Verdict::Err(_)), "{v:?}");
}

#[test]
#[ignore = "known finding: 1 GiB inflate bomb takes over 10 s in a debug build (bytes generated, not committed)"]
fn finding_flate_bomb_1gib_hang() {
    let v = child_verdict(generated("flate/bomb-1GiB"), "finding/bomb-1GiB", 2);
    assert!(matches!(v, Verdict::Ok | Verdict::Err(_)), "{v:?}");
}

#[test]
#[ignore = "known finding: 10 MB text operator flood takes over 10 s in a debug build (bytes generated, not committed)"]
fn finding_operators_10mb_text_hang() {
    let v = child_verdict(
        generated("operators/10MB-text-ops"),
        "finding/ops-10MB-text",
        2,
    );
    assert!(matches!(v, Verdict::Ok | Verdict::Err(_)), "{v:?}");
}

#[test]
#[ignore = "known finding: hayro crypto panics on empty /O /U; the engine catches it and reports an error"]
fn finding_encrypt_empty_o_u_internal_panic() {
    let bytes = finding_bytes("encrypt-v2-empty-o-u.pdf");
    let panicked = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&panicked);
    std::panic::set_hook(Box::new(move |_: &std::panic::PanicHookInfo<'_>| {
        flag.store(true, Ordering::SeqCst)
    }));
    let outcome = pipeline(&bytes);
    let _ = std::panic::take_hook();
    assert!(
        !panicked.load(Ordering::SeqCst),
        "the engine panicked internally: {outcome:?}"
    );
}
