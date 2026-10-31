//! In-process entry point for the Real World Minecraft mod.
//!
//! Added for the Real World mod; not part of upstream Arnis. `run` follows
//! `run_cli` in `main.rs` step for step, with three differences that matter
//! inside a running game: errors are returned instead of exiting the process,
//! only new Java worlds are supported, and the result is reported as JSON.
//! The generation itself is the unchanged Arnis code.

use crate::args::{self, Args};
use crate::progress;
use crate::{
    data_processing, elevation, elevation_data, floodfill_cache, ground, map_transformation,
    mapillary, mode_filter, osm_parser, overture, projection, retrieve_data, world_editor,
    world_utils,
};
use clap::Parser;
use serde_json::json;
use std::ffi::{c_char, CStr, CString};
use std::panic::{self, AssertUnwindSafe};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, Once};

/// What a finished generation hands back to the mod.
struct Generated {
    world_path: PathBuf,
    spawn: Option<(i32, i32, i32)>,
    area: (i32, i32, i32, i32),
    extended: bool,
}

fn run(argv: Vec<String>, world_name: Option<String>) -> Result<Generated, String> {
    crate::map_renderer::set_live(None);
    let result = run_inner(argv, world_name);
    crate::map_renderer::set_live(None);
    result
}

fn run_inner(argv: Vec<String>, world_name: Option<String>) -> Result<Generated, String> {
    floodfill_cache::configure_rayon_thread_pool(0.9);
    elevation_data::cleanup_old_cached_tiles();

    let mut args = Args::try_parse_from(argv).map_err(|e| e.to_string())?;
    args::apply_body_defaults(&mut args);
    args::apply_feature_needs(&mut args);
    args::validate_args(&args)?;

    if args.bedrock || args.luanti || args.mapillary_probe {
        return Err("Only Java worlds can be generated in game.".into());
    }
    if args.cave_zone_map.is_some() {
        return Err("--cave-zone-map is not available in game.".into());
    }
    let base_dir = args
        .path
        .clone()
        .ok_or("The saves folder (--output-dir) is missing.")?;

    world_editor::set_world_bounds(
        ground::extended_min_y_for(&args),
        ground::world_top_y_for(&args),
    );

    let skip_objects = args.skip_objects();
    let skip_osm = args.skip_osm();
    let (mut preloaded_osm, mut effective_bbox) = match (skip_osm, args.file.as_deref()) {
        (false, Some(file)) => {
            let (data, file_bounds) = retrieve_data::fetch_data_from_file(file)
                .map_err(|e| format!("Failed to load OSM file: {e}"))?;
            let bbox = osm_parser::resolve_bbox(args.bbox, file_bounds, &data)
                .ok_or("Could not derive a bounding box from the OSM file.")?;
            (Some(data), bbox)
        }
        _ => (None, args.bbox.ok_or("A bounding box is required.")?),
    };

    let world_format = world_editor::WorldFormat::JavaAnvil;
    progress::emit_gui_progress_update(0.5, "Creating world");

    // One World (--one-world): the world named by --world-name is created, or extended with
    // this area, in a frame pinned to real-world coordinates. Its lock is held until the end.
    let mut _one_world_lock = None;
    let created: bool;
    let generation_path = if args.one_world {
        let name = args
            .world_name
            .as_deref()
            .and_then(world_utils::world_folder_name)
            .unwrap_or_else(|| crate::one_world::DEFAULT_WORLD_NAME.to_string());
        let world_dir = base_dir.join(name);
        let session = crate::one_world::prepare(&world_dir, &effective_bbox, &mut args)?;
        effective_bbox = session.llbbox;
        world_editor::set_world_bounds(
            ground::extended_min_y_for(&args),
            ground::world_top_y_for(&args),
        );
        created = session.created;
        _one_world_lock = Some(session.lock);
        if args.disable_height_limit && created {
            if let Err(e) = world_utils::install_tall_datapack(&world_dir) {
                let _ = std::fs::remove_dir_all(&world_dir);
                return Err(format!("Failed to install tall-world datapack: {e}"));
            }
        }
        world_utils::check_run_size(&args, &effective_bbox, world_format, &world_dir)
            .map_err(|e| e.detail)?;
        world_dir
    } else {
        world_utils::check_run_size(&args, &effective_bbox, world_format, &base_dir)
            .map_err(|e| e.detail)?;
        created = true;
        PathBuf::from(world_utils::create_new_world_with_name(
            &base_dir,
            world_name.as_deref(),
        )?)
    };
    let extending = args.one_world_run.as_ref().is_some_and(|r| r.extending);
    // From here on a failure leaves a half-made world behind; a world this run created is
    // removed, an extended one keeps what it had.
    let fail = |message: String| -> Result<Generated, String> {
        if created {
            let _ = std::fs::remove_dir_all(&generation_path);
        }
        Err(message)
    };

    let facade_job = mapillary::FacadeJob::start(&args, effective_bbox);

    let (peak_tx, peaks) = elevation::PeakSource::channel();
    let fetched = std::thread::scope(|s| {
        let peak_tx = peak_tx;
        let overture_handle = s.spawn(|| {
            if args.overture && !skip_objects {
                overture::fetch_overture_buildings(
                    &effective_bbox,
                    &projection::ProjectionSpec::from_args(&args),
                    args.overture_source,
                    args.debug,
                )
            } else {
                overture::OvertureData::default()
            }
        });
        let ground_handle = s.spawn(|| ground::generate_ground_data(&args, effective_bbox, &peaks));

        let raw_data = if skip_osm {
            osm_parser::OsmData::empty()
        } else if let Some(data) = preloaded_osm.take() {
            data
        } else {
            match retrieve_data::fetch_osm_data(
                effective_bbox,
                args.scale,
                args.debug,
                args.downloader.as_str(),
                args.save_json_file.as_deref(),
                &args.osm_tiles_url,
                !args.no_tile_archive,
            ) {
                Ok(data) => data,
                Err(e) => {
                    drop(peak_tx);
                    let _ = ground_handle.join();
                    let _ = overture_handle.join();
                    return Err(format!("Failed to fetch map data: {e}"));
                }
            }
        };
        let _ = peak_tx.send(elevation::TerrainHints::from_osm(&raw_data, &args));

        let overture_data = overture_handle
            .join()
            .unwrap_or_else(|_| overture::OvertureData::default());
        if elevation::TerrainHints::wants_overture(&args) {
            let _ = peak_tx.send(elevation::TerrainHints::from_overture(
                &raw_data,
                &args,
                &overture_data,
            ));
        }
        drop(peak_tx);
        let ground = ground_handle
            .join()
            .map_err(|_| "Terrain fetch failed.".to_string())?;
        Ok((raw_data, overture_data, ground))
    });
    let (raw_data, overture_data, mut ground) = match fetched {
        Ok(v) => v,
        Err(e) => return fail(e),
    };

    if let Err(e) = ground::settle_build_height(
        &mut args,
        &ground,
        &effective_bbox,
        world_format,
        &base_dir,
        Some(generation_path.as_path()),
    ) {
        return fail(e.to_string());
    }
    let args = args;

    let (mut parsed_elements, mut xzbbox, outline_suppression, part_groups, housing_parts) =
        osm_parser::parse_osm_data(
            raw_data,
            effective_bbox,
            args.debug,
            &projection::ProjectionSpec::from_args(&args),
        );

    let overture::OvertureData {
        elements: overture_elements,
        hints: overture_hints,
        ..
    } = overture_data;
    overture_hints.apply(&mut parsed_elements);
    if !overture_elements.is_empty() {
        let unique = overture::deduplicate_against_osm(overture_elements, &parsed_elements);
        parsed_elements.extend(unique);
    }
    osm_parser::sort_by_priority(&mut parsed_elements);

    ground.apply_osm_water_override(&parsed_elements, &xzbbox);
    ground.apply_osm_land_override(&parsed_elements, &xzbbox, args.scale);
    ground.apply_bridge_land_cover_repair(&parsed_elements, &xzbbox, args.scale);
    ground.finish_land_cover(args.mode);

    mode_filter::retain_for_mode(&mut parsed_elements, args.mode);
    let outline_suppression =
        mode_filter::outline_suppression_for_mode(outline_suppression, args.mode);

    map_transformation::transform_map(&mut parsed_elements, &mut xzbbox, &mut ground);
    let pre_rotation_bbox = xzbbox.clone();
    if args.rotation.abs() > f64::EPSILON {
        if let Err(e) = map_transformation::rotate::rotate_world(
            args.rotation,
            &mut parsed_elements,
            &mut xzbbox,
            &mut ground,
        ) {
            return fail(format!("Rotation failed: {e}"));
        }
    }

    let spawn_point = match (args.spawn_lat, args.spawn_lng) {
        (Some(lat), Some(lng)) => {
            use crate::coordinate_system::geographic::LLPoint;
            let point = match LLPoint::new(lat, lng) {
                Ok(p) => p,
                Err(e) => return fail(format!("Invalid spawn coordinates: {e}")),
            };
            let (transformer, pre_rot_bbox) =
                match projection::ProjectionSpec::from_args(&args).transformer(&effective_bbox) {
                    Ok(t) => t,
                    Err(e) => return fail(format!("Failed to convert spawn point: {e}")),
                };
            let xz = transformer.transform_point(point);
            Some(map_transformation::rotate::rotate_xz_point(
                xz.x,
                xz.z,
                args.rotation,
                &pre_rot_bbox,
            ))
        }
        _ => None,
    };
    let java_spawn = spawn_point.or_else(|| {
        Some(map_transformation::rotate::rotate_xz_point(
            pre_rotation_bbox.min_x() + 1,
            pre_rotation_bbox.min_z() + 1,
            args.rotation,
            &pre_rotation_bbox,
        ))
    });
    let spawn_y = java_spawn.map(|(sx, sz)| {
        use crate::coordinate_system::cartesian::XZPoint;
        let rel = XZPoint::new(sx - xzbbox.min_x(), sz - xzbbox.min_z());
        ground.level(rel) + 3
    });
    let area = (
        xzbbox.min_x(),
        xzbbox.min_z(),
        xzbbox.max_x(),
        xzbbox.max_z(),
    );

    let options = data_processing::GenerationOptions {
        path: generation_path.clone(),
        format: world_format,
        level_name: None,
        spawn_point: if extending { spawn_point } else { java_spawn },
        luanti_game: None,
        ground_level: args.ground_level,
        facades: facade_job,
    };
    if let Err(e) = data_processing::generate_world_with_options(
        parsed_elements,
        xzbbox,
        effective_bbox,
        ground,
        &args,
        options,
        outline_suppression,
        part_groups,
        housing_parts,
    ) {
        return fail(e.to_string());
    }

    let spawn = match (java_spawn, spawn_y) {
        (Some((x, z)), Some(y)) if !extending => {
            if let Err(e) = world_utils::set_spawn_in_level_dat(&generation_path, x, y, z) {
                eprintln!("Warning: Failed to set spawn point in level.dat: {e}");
            }
            Some((x, y, z))
        }
        _ => None,
    };
    progress::emit_gui_progress_update(100.0, "Done");
    Ok(Generated {
        world_path: generation_path,
        spawn,
        area,
        extended: extending,
    })
}

// ---------------------------------------------------------------------------
// C ABI used by the mod through Java's Foreign Function & Memory API.
// ---------------------------------------------------------------------------

#[derive(serde::Deserialize)]
struct Request {
    name: Option<String>,
    args: Vec<String>,
    /// Worker threads for this run; 0 or absent keeps Arnis's own pool (90% of the cores).
    #[serde(default)]
    threads: usize,
    /// Below-normal OS priority for this run's threads, so the game stays smooth.
    #[serde(default)]
    low_priority: bool,
}

/// Lowers the calling thread's OS priority one step (Windows only; elsewhere a no-op).
fn lower_thread_priority() {
    #[cfg(windows)]
    unsafe {
        use windows::Win32::System::Threading::{
            GetCurrentThread, SetThreadPriority, THREAD_PRIORITY_BELOW_NORMAL,
        };
        let _ = SetThreadPriority(GetCurrentThread(), THREAD_PRIORITY_BELOW_NORMAL);
    }
}

/// Runs the generation in a pool of its own when the mod asks for a lighter load. The pool
/// and its threads go away with the run, so the next one can choose differently.
fn run_with_load(
    argv: Vec<String>,
    name: Option<String>,
    threads: usize,
    low_priority: bool,
) -> Result<Generated, String> {
    if threads == 0 && !low_priority {
        return run(argv, name);
    }
    if low_priority {
        lower_thread_priority();
    }
    let count = if threads > 0 {
        threads
    } else {
        std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4)
    };
    match rayon::ThreadPoolBuilder::new()
        .num_threads(count)
        .thread_name(|i| format!("arnis-worker-{i}"))
        .start_handler(move |_| {
            if low_priority {
                lower_thread_priority();
            }
        })
        .build()
    {
        Ok(pool) => pool.install(|| run(argv, name)),
        Err(_) => run(argv, name),
    }
}

static RUNNING: AtomicBool = AtomicBool::new(false);
static PANIC_MESSAGE: Mutex<String> = Mutex::new(String::new());
static HOOK: Once = Once::new();

fn install_panic_hook() {
    HOOK.call_once(|| {
        let previous = panic::take_hook();
        panic::set_hook(Box::new(move |info| {
            let payload = info.payload();
            if payload.downcast_ref::<&str>() == Some(&progress::CANCEL_PANIC) {
                return;
            }
            let text = payload
                .downcast_ref::<&str>()
                .map(|s| s.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown error".into());
            let place = info
                .location()
                .map(|l| format!(" ({}:{})", l.file(), l.line()))
                .unwrap_or_default();
            *PANIC_MESSAGE.lock().unwrap_or_else(|e| e.into_inner()) = format!("{text}{place}");
            previous(info);
        }));
    });
}

fn into_c_string(value: serde_json::Value) -> *mut c_char {
    CString::new(value.to_string())
        .unwrap_or_else(|_| CString::new("{\"ok\":false,\"error\":\"bad result\"}").unwrap())
        .into_raw()
}

/// Runs one generation and blocks until it ends. `request_json` is
/// `{"name": "<world folder name>", "args": ["--bbox", ...]}`: the world name and
/// the command-line arguments without the program name. Returns a JSON object
/// the caller must release with `arnis_free`.
///
/// # Safety
/// `request_json` must be a valid NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn arnis_generate(request_json: *const c_char) -> *mut c_char {
    if RUNNING.load(Ordering::Acquire) {
        return into_c_string(json!({"ok": false, "error": "A generation is already running."}));
    }
    // Before the flag: a caller that sees a run going never reads the last run's progress.
    progress::reset();
    if RUNNING.swap(true, Ordering::AcqRel) {
        return into_c_string(json!({"ok": false, "error": "A generation is already running."}));
    }
    install_panic_hook();
    PANIC_MESSAGE.lock().unwrap_or_else(|e| e.into_inner()).clear();

    let parsed: Result<Request, String> = if request_json.is_null() {
        Err("No request.".into())
    } else {
        CStr::from_ptr(request_json)
            .to_str()
            .map_err(|e| e.to_string())
            .and_then(|s| serde_json::from_str(s).map_err(|e| e.to_string()))
    };

    let result = match parsed {
        Err(e) => json!({"ok": false, "error": e}),
        Ok(request) => {
            let mut argv = vec!["arnis".to_string()];
            argv.extend(request.args);
            let (threads, low) = (request.threads, request.low_priority);
            match panic::catch_unwind(AssertUnwindSafe(|| {
                run_with_load(argv, request.name, threads, low)
            })) {
                Ok(Ok(done)) => json!({
                    "ok": true,
                    "world": done.world_path.display().to_string(),
                    "spawn": done.spawn.map(|(x, y, z)| vec![x, y, z]),
                    "area": [done.area.0, done.area.1, done.area.2, done.area.3],
                    "extended": done.extended,
                }),
                Ok(Err(e)) => {
                    let detail = progress::last_error();
                    json!({"ok": false, "error": e, "detail": detail})
                }
                Err(_) if progress::is_cancelled() => {
                    json!({"ok": false, "cancelled": true, "error": "Cancelled"})
                }
                Err(_) => {
                    let message = PANIC_MESSAGE.lock().unwrap_or_else(|e| e.into_inner()).clone();
                    json!({"ok": false, "error": format!("Arnis crashed: {message}")})
                }
            }
        }
    };
    RUNNING.store(false, Ordering::Release);
    into_c_string(result)
}

/// The city being built, for drawing it live: writes out_w, out_h, step, min_x, min_z, max_x,
/// max_z and a version into `out` (8 values). False while no run has a preview yet.
///
/// # Safety
/// `out` must point to 8 writable i64.
#[no_mangle]
pub unsafe extern "C" fn arnis_live_info(out: *mut i64) -> bool {
    if out.is_null() {
        return false;
    }
    match crate::map_renderer::live_info() {
        Some(info) => {
            std::ptr::copy_nonoverlapping(info.as_ptr(), out, 8);
            true
        }
        None => false,
    }
}

/// Copies the city being built: `len` ABGR colours and `len` heights (out_w * out_h from
/// `arnis_live_info`). False when nothing matches that size any more.
///
/// # Safety
/// `colors` and `heights` must point to `len` writable values each.
#[no_mangle]
pub unsafe extern "C" fn arnis_live_copy(colors: *mut u32, heights: *mut i16, len: usize) -> bool {
    if colors.is_null() || heights.is_null() {
        return false;
    }
    let colors = std::slice::from_raw_parts_mut(colors, len);
    let heights = std::slice::from_raw_parts_mut(heights, len);
    crate::map_renderer::live_copy(colors, heights)
}

/// Releases a string returned by `arnis_generate`.
///
/// # Safety
/// `ptr` must come from `arnis_generate` and be released only once.
#[no_mangle]
pub unsafe extern "C" fn arnis_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        drop(CString::from_raw(ptr));
    }
}

/// Current progress, 0 to 100.
#[no_mangle]
pub extern "C" fn arnis_progress() -> f64 {
    progress::progress()
}

/// Copies the current stage message into `buf` (UTF-8, NUL-terminated when it
/// fits) and returns its length in bytes.
///
/// # Safety
/// `buf` must point to at least `capacity` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn arnis_message(buf: *mut u8, capacity: usize) -> usize {
    let message = progress::message();
    let bytes = message.as_bytes();
    if !buf.is_null() && capacity > 0 {
        let n = bytes.len().min(capacity - 1);
        std::ptr::copy_nonoverlapping(bytes.as_ptr(), buf, n);
        *buf.add(n) = 0;
    }
    bytes.len()
}

/// Asks a running generation to stop at the next progress report.
#[no_mangle]
pub extern "C" fn arnis_cancel() {
    progress::request_cancel();
}

/// Whether a generation is running.
#[no_mangle]
pub extern "C" fn arnis_running() -> bool {
    RUNNING.load(Ordering::Acquire)
}

/// Arnis version this library was built from.
#[no_mangle]
pub extern "C" fn arnis_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}
