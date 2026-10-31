//! Arnis as a native library for the Real World Minecraft mod.
//!
//! Added for the Real World mod; not part of upstream Arnis. The module list
//! matches `main.rs`; the GUI-only modules are left out and `progress` is the
//! in-game version. The exported C functions live in `embedded.rs`.
#![allow(dead_code, unused_imports)]

mod args;
mod bedrock_addon;
mod bedrock_block_map;
mod bench;
mod biome;
mod block_definitions;
mod block_palette;
mod bresenham;
mod building_facades;
mod canopy;
mod caves;
mod celestial;
mod climate;
mod clipping;
mod colors;
mod coordinate_system;
mod data_processing;
mod datapack;
mod decals;
mod deferred_placement;
mod deterministic_rng;
mod distant_horizons;
mod ecoregion;
mod element_processing;
mod elevation;
mod elevation_data;
mod embedded;
mod floodfill;
mod floodfill_cache;
mod geo_grid;
mod grid_ops;
mod ground;
mod ground_decoration;
mod ground_generation;
mod land_cover;
mod landmarks;
mod luanti_block_map;
mod map_item;
mod map_item_palette;
mod map_preview;
mod map_renderer;
mod map_transformation;
mod mapillary;
mod mode_filter;
mod models_3d;
mod net;
mod one_world;
mod ore_generation;
mod osm_parser;
mod osm_tiles;
mod overture;
#[path = "embedded_progress.rs"]
mod progress;
mod projection;
mod retrieve_data;
mod structures;
mod terrain_surface;
#[cfg(test)]
mod test_utilities;
mod tile;
mod trees;
mod version_check;
mod voxy;
mod water_depth;
mod water_steps;
mod world_editor;
mod world_utils;

#[global_allocator]
static GLOBAL: mimalloc::MiMalloc = mimalloc::MiMalloc;
