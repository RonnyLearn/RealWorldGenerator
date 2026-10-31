//! Progress reporting for the embedded (in-game) build of Arnis.
//!
//! Added for the Real World mod; not part of upstream Arnis. It replaces the
//! Tauri `progress` module with the same function names, so the generation code
//! is used unchanged. The mod polls the shared state through `embedded.rs`.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;

/// Mirrors the GUI module: a value that changes the message but not the bar.
pub const MESSAGE_ONLY: f64 = -1.0;

/// Panic payload used to unwind out of a generation the player cancelled.
pub const CANCEL_PANIC: &str = "arnis-embedded-cancelled";

static PROGRESS_BITS: AtomicU64 = AtomicU64::new(0);
static MESSAGE: Mutex<String> = Mutex::new(String::new());
static ERROR: Mutex<String> = Mutex::new(String::new());
static CANCEL: AtomicBool = AtomicBool::new(false);

pub fn reset() {
    PROGRESS_BITS.store(0f64.to_bits(), Ordering::Relaxed);
    set_message("");
    ERROR.lock().unwrap_or_else(|e| e.into_inner()).clear();
    CANCEL.store(false, Ordering::Relaxed);
}

pub fn progress() -> f64 {
    f64::from_bits(PROGRESS_BITS.load(Ordering::Relaxed))
}

pub fn message() -> String {
    MESSAGE.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

pub fn last_error() -> String {
    ERROR.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

pub fn request_cancel() {
    CANCEL.store(true, Ordering::Relaxed);
}
pub fn is_cancelled() -> bool {
    CANCEL.load(Ordering::Relaxed)
}

fn set_message(message: &str) {
    let mut guard = MESSAGE.lock().unwrap_or_else(|e| e.into_inner());
    guard.clear();
    guard.push_str(message);
}

/// Every stage reports here, which makes it the place where a cancel takes effect.
#[inline]
pub fn check_cancel() {
    if is_cancelled() {
        std::panic::panic_any(CANCEL_PANIC);
    }
}

pub fn emit_gui_progress_update(progress: f64, message: &str) {
    check_cancel();
    if progress > 0.0 {
        // Stages run in parallel and report out of order; the bar only moves forward.
        let mut current = PROGRESS_BITS.load(Ordering::Relaxed);
        while f64::from_bits(current) < progress {
            match PROGRESS_BITS.compare_exchange_weak(
                current,
                progress.to_bits(),
                Ordering::Relaxed,
                Ordering::Relaxed,
            ) {
                Ok(_) => break,
                Err(now) => current = now,
            }
        }
    }
    if !message.is_empty() {
        set_message(message);
    }
}

pub fn emit_gui_progress_update_ex(progress: f64, message: &str, _streaming: bool) {
    emit_gui_progress_update(progress, message);
}

pub fn emit_gui_error(message: &str) {
    let mut guard = ERROR.lock().unwrap_or_else(|e| e.into_inner());
    guard.clear();
    guard.push_str(message);
}

pub fn emit_world_name_update(_name: &str) {}

pub fn emit_map_preview_ready() {}

pub fn emit_show_in_folder(_path: &str) {}

/// The embedded build has no Tauri window. Code that asks this falls back to its
/// CLI behaviour, which is synchronous and prints to the game log.
pub fn is_running_with_gui() -> bool {
    false
}
