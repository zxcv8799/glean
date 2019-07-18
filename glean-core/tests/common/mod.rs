// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

// #[allow(dead_code)] is required on this module as a workaround for
// https://github.com/rust-lang/rust/issues/46379
#![allow(dead_code)]
use glean_core::{Glean, Result};

use std::fs::{read_dir, File};
use std::io::{BufRead, BufReader};
use std::path::Path;

use chrono;
use chrono::offset::TimeZone;
use iso8601;
use iso8601::Date::YMD;
use serde_json::Value as JsonValue;

use ctor::ctor;

/// Initialize the logger for all tests without individual tests requiring to call the init code.
/// Log output can be controlled via the environment variable `RUST_LOG` for the `glean_core` crate,
/// e.g.:
///
/// ```
/// export RUST_LOG=glean_core=debug
/// ```
#[ctor]
fn enable_test_logging() {
    // When testing we want all logs to go to stdout/stderr by default,
    // without requiring each individual test to activate it.
    // This only applies to glean-core tests, users of the main library still need to call
    // `glean_enable_logging` of the FFI component (automatically done by the platform wrappers).
    let _ = env_logger::builder().is_test(true).try_init();
}

pub fn tempdir() -> (tempfile::TempDir, String) {
    let t = tempfile::tempdir().unwrap();
    let name = t.path().display().to_string();
    (t, name)
}

pub const GLOBAL_APPLICATION_ID: &str = "org.mozilla.glean.test.app";

// Create a new instance of Glean with a temporary directory.
// We need to keep the `TempDir` alive, so that it's not deleted before we stop using it.
pub fn new_glean() -> (Glean, tempfile::TempDir) {
    let dir = tempfile::tempdir().unwrap();
    let tmpname = dir.path().display().to_string();

    let glean = Glean::new(&tmpname, GLOBAL_APPLICATION_ID, true).unwrap();

    (glean, dir)
}

/// Convert an iso8601::DateTime to a chrono::DateTime<FixedOffset>
pub fn iso8601_to_chrono(datetime: &iso8601::DateTime) -> chrono::DateTime<chrono::FixedOffset> {
    if let YMD { year, month, day } = datetime.date {
        return chrono::FixedOffset::east(datetime.time.tz_offset_hours * 3600)
            .ymd(year, month, day)
            .and_hms_milli(
                datetime.time.hour,
                datetime.time.minute,
                datetime.time.second,
                datetime.time.millisecond,
            );
    };
    panic!("Unsupported datetime format");
}

/// Get a vector of the currently queued pings.
pub fn get_queued_pings(data_path: &Path) -> Result<Vec<(String, JsonValue)>> {
    let pings_dir = data_path.join("pings");
    let entries = read_dir(&pings_dir)?;
    Ok(entries
        .filter_map(|entry| entry.ok())
        .filter(|entry| match entry.file_type() {
            Ok(file_type) => file_type.is_file(),
            Err(_) => false,
        })
        .filter_map(|entry| File::open(entry.path()).ok())
        .filter_map(|file| {
            let mut lines = BufReader::new(file).lines();
            if let (Some(Ok(url)), Some(Ok(json))) = (lines.next(), lines.next()) {
                if let Ok(parsed_json) = serde_json::from_str::<JsonValue>(&json) {
                    Some((url, parsed_json))
                } else {
                    None
                }
            } else {
                None
            }
        })
        .collect())
}
