//! Logs: one JSON object per line on stdout, the shape the platform's log collector takes
//! apart (its RUNBOOK, Logs): `ts` becomes the line's time, `msg` its message, and every
//! other key (`level`, `request_id`, `route`, `status`...) a field to search by. Each line
//! logged while a request is served carries that request's ID (`REQUEST_ID`, which the
//! request middleware sets). The same keys as the Python and Java templates.
//!
//! Small on purpose: a bigger app moves to `tracing` with a JSON subscriber, keeping these
//! keys.

use std::io::Write;
use std::time::SystemTime;

use serde_json::{Map, Value, json};

tokio::task_local! {
    /// The ID of the request the current task is serving.
    pub static REQUEST_ID: String;
}

/// The line `log` writes: the common keys, then `fields`.
pub fn line(level: &str, logger: &str, msg: &str, fields: Map<String, Value>) -> String {
    let mut entry = json!({ "ts": now(), "level": level, "logger": logger, "msg": msg });
    let object = entry.as_object_mut().expect("a JSON object");
    if let Ok(id) = REQUEST_ID.try_with(Clone::clone) {
        object.insert("request_id".into(), id.into());
    }
    object.extend(fields);
    entry.to_string()
}

/// Writes one line to stdout. `level` is `debug`, `info`, `warn` or `error`; below
/// `LOG_LEVEL` (default `info`) nothing is written.
pub fn log(level: &str, logger: &str, msg: &str, fields: Map<String, Value>) {
    if rank(level) >= rank(&std::env::var("LOG_LEVEL").unwrap_or_else(|_| "info".into())) {
        let _ = writeln!(
            std::io::stdout().lock(),
            "{}",
            line(level, logger, msg, fields)
        );
    }
}

fn rank(level: &str) -> u8 {
    match level.to_ascii_lowercase().as_str() {
        "debug" => 0,
        "warn" => 2,
        "error" => 3,
        _ => 1,
    }
}

/// UTC, RFC 3339 with milliseconds: 2026-11-19T06:37:08.189Z.
fn now() -> String {
    humantime::format_rfc3339_millis(SystemTime::now()).to_string()
}
