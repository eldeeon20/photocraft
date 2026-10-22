//! Android application runtime and eframe runner setup.

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use photocraft_doc::Document;
use photocraft_engine::Session;
use photocraft_ui_egui::file_dialog::FileDialogRequest;
use photocraft_ui_egui::{PhotocraftApp, Services};

use crate::mobile;

/// Starts the PhotoCraft Android runner.
pub fn start(android_app: android_activity::AndroidApp) {
    let internal_path = android_app.internal_data_path().map(PathBuf::from);

    let mut options = eframe::NativeOptions::default();
    options.android_app = Some(android_app);

    // On Android, use GLES backend by default to avoid ANativeWindow double-binding
    // crashes when probing Vulkan on emulators, Waydroid, and older GPUs.
    if let eframe::egui_wgpu::WgpuSetup::CreateNew(ref mut create) = options.wgpu_options.wgpu_setup {
        let use_vulkan = std::env::var("PHOTOCRAFT_VULKAN").map(|v| v == "1" || v == "true").unwrap_or(false);
        if use_vulkan {
            create.instance_descriptor.backends = eframe::wgpu::Backends::VULKAN;
        } else {
            create.instance_descriptor.backends = eframe::wgpu::Backends::GL;
        }
    }

    photocraft_ui_egui::gpu_canvas::use_adapter_limits(&mut options.wgpu_options.wgpu_setup);

    let result = eframe::run_native(
        "PhotoCraft",
        options,
        Box::new(move |cc| {
            photocraft_ui_egui::PhotocraftApp::setup_context(&cc.egui_ctx, photocraft_ui_egui::theme::ThemeKind::Pro);
            cc.egui_ctx.global_style_mut(mobile::configure_mobile_style);

            let mobile_state: mobile::SharedMobileState = Arc::new(Mutex::new(mobile::MobileState::default()));
            if let Some(dir) = &internal_path {
                let root = dir.join("PhotoCraft");
                let _ = std::fs::create_dir_all(root.join("req"));
                let _ = std::fs::create_dir_all(root.join("res"));
                if let Ok(mut state) = mobile_state.lock() {
                    state.files_dir = root;
                }
            }

            let services = create_mobile_services(internal_path.clone(), mobile_state.clone());
            let mut app = PhotocraftApp::new(Session::new(), services);

            // Apply phone layout defaults (canvas-first, collapsed dock, mobile action bar).
            mobile::apply_mobile_defaults(&mut app);

            // Say whether the Kotlin shell is reachable: it writes boot.txt in its data dir.
            if let Ok(state) = mobile_state.lock() {
                let dir = state.files_dir.clone();
                let boot = dir.join("boot.txt");
                app.ui.status = match std::fs::read_to_string(&boot) {
                    Ok(text) => format!("shell: {}", text.lines().next().unwrap_or("alive")),
                    Err(_) => format!("shell silent; looked in {}", dir.display()),
                };
                app.ui.status_error = false;
            }

            if let Some(rs) = cc.wgpu_render_state.clone() {
                log::info!("photocraft-android: wgpu backend {:?}", rs.adapter.get_info().backend);
                app.set_wgpu(rs);
            }

            Ok(Box::new(MobileShell { app, state: mobile_state }))
        }),
    );

    if let Err(e) = result {
        log::error!("PhotoCraft failed to run on Android: {e}");
    }
}

/// The Android application wrapper.
struct MobileShell {
    app: PhotocraftApp,
    state: mobile::SharedMobileState,
}

impl eframe::App for MobileShell {
    fn logic(&mut self, ctx: &egui::Context, frame: &mut eframe::Frame) {
        self.app.logic(ctx, frame);
    }

    fn ui(&mut self, ui: &mut egui::Ui, frame: &mut eframe::Frame) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        let ctx = ui.ctx().clone();

        // 1. Mobile top action bar (hidden during fullscreen)
        if !state.is_fullscreen {
            mobile::render_mobile_action_bar(&mut self.app, &mut state, ui);
        }

        // 2. Main PhotoCraft canvas & panels
        self.app.ui(ui, frame);

        // 2b. The shell answers file dialogs from another process context: look for its results.
        mobile::poll_saf(&mut self.app, &mut state);
        ctx.request_repaint_after(std::time::Duration::from_millis(250));

        // 3. Floating overlays
        mobile::render_fullscreen_exit_button(&ctx, &mut state, &mut self.app);
        mobile::render_file_picker_dialog(&ctx, &mut state);
        mobile::render_save_dialog(&ctx, &mut state);
        mobile::render_status_banner(&mut self.app, &mut state, &ctx);
    }
}

/// Creates platform services adapted for the Android app sandbox.
fn create_mobile_services(storage_dir: Option<PathBuf>, mobile_state: mobile::SharedMobileState) -> Services {
    let mut services = Services::default();

    if let Some(ref dir) = storage_dir {
        let prefs_dir = dir.join("prefs");
        let _ = std::fs::create_dir_all(&prefs_dir);
        let prefs_file = prefs_dir.join("photocraft_prefs.json");

        let read_path = prefs_file.clone();
        services.load_prefs = Some(Box::new(move || std::fs::read_to_string(&read_path).ok()));

        let write_path = prefs_file;
        services.save_prefs = Some(Box::new(move |content: &str| {
            std::fs::write(&write_path, content).map_err(|e| e.to_string())
        }));
    }

    // Import service for decoding image formats.
    services.import = Some(Box::new(|name: &str, bytes: &[u8], max_svg_group_depth: usize| {
        photocraft_io::import_with_svg_group_depth(name, bytes, max_svg_group_depth)
            .map(|r| (r.document, r.warnings))
            .map_err(|e| e.to_string())
    }));

    // Export service for saving images.
    services.export = Some(Box::new(|doc: &Document, path: &str, settings: &photocraft_ui_egui::ExportSettings| {
        let mut opts = photocraft_io::ExportOptions::default();
        if let Some(q) = settings.jpeg_quality {
            opts.encode.jpeg_quality = q;
        }
        opts.encode.webp_lossless = settings.webp_lossless;
        if let Some(q) = settings.webp_quality {
            opts.encode.webp_quality = q;
        }
        opts.tiff_layers = settings.tiff_layers;
        opts.xmp = if settings.xmp_all {
            photocraft_io::XmpEmbed::All
        } else {
            photocraft_io::XmpEmbed::None
        };
        photocraft_io::export(doc, path, &opts)
            .map(|r| (r.bytes, r.warnings))
            .map_err(|e| e.to_string())
    }));

    // File dialogs: the Kotlin shell answers open/folder picks with the system picker; saves use
    // the in-app name dialog (the write service hands the bytes to the picker when it must).
    let state_dialog = mobile_state;
    let req_root = storage_dir.as_ref().map(|d| d.join("PhotoCraft").join("req"));
    services.file_dialog = Some(Box::new(move |request, _parent, reply| {
        if let Ok(mut state) = state_dialog.lock() {
            match request {
                FileDialogRequest::Open { initial_dir, .. } => {
                    state.pending_open = Some(reply);
                    match &req_root {
                        Some(req) => {
                            let _ = std::fs::create_dir_all(req);
                            let _ = std::fs::write(req.join("open.req"), "*/*\n");
                        }
                        None => {
                            state.file_picker_open = true;
                            state.selected_file = None;
                            state.current_dir = initial_dir
                                .map(PathBuf::from)
                                .filter(|p| p.is_dir())
                                .unwrap_or_else(mobile::default_browse_dir);
                        }
                    }
                }
                FileDialogRequest::Folder { .. } => {
                    state.pending_folder = Some(reply);
                    if let Some(req) = &req_root {
                        let _ = std::fs::create_dir_all(req);
                        let _ = std::fs::write(req.join("open.req"), "folder\n");
                    }
                }
                FileDialogRequest::Save { suggested } => {
                    state.save_filename = suggested;
                    state.save_dialog_open = true;
                    state.pending_save = Some(reply);
                }
            }
        }
    }));

    // Write service: raw paths where Android allows it (All files access), otherwise stage the
    // bytes and let the Kotlin shell write them into the document the user picks with SAF.
    let write_root = storage_dir.as_ref().map(|d| d.join("PhotoCraft"));
    services.write = Some(Box::new(move |path: &str, bytes: &[u8]| {
        let p = std::path::Path::new(path);
        if let Some(parent) = p.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        if std::fs::write(p, bytes).is_ok() {
            return Ok(());
        }
        let root = write_root.as_ref().ok_or_else(|| format!("cannot write {path}"))?;
        let outbox = root.join("outbox");
        let req = root.join("req");
        std::fs::create_dir_all(&outbox).map_err(|e| e.to_string())?;
        std::fs::create_dir_all(&req).map_err(|e| e.to_string())?;
        let name = p.file_name().and_then(|n| n.to_str()).unwrap_or("export.png");
        let staged = outbox.join(name);
        std::fs::write(&staged, bytes).map_err(|e| e.to_string())?;
        std::fs::write(
            req.join("save.req"),
            format!("{}\n{}\n{}\n", staged.display(), name, mime_for(name)),
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    }));

    services
}

/// The MIME type the system picker shows for a file name.
fn mime_for(name: &str) -> &'static str {
    match name.rsplit('.').next().unwrap_or("").to_ascii_lowercase().as_str() {
        "png" => "image/png",
        "jpg" | "jpeg" => "image/jpeg",
        "webp" => "image/webp",
        "tif" | "tiff" => "image/tiff",
        "bmp" => "image/bmp",
        "gif" => "image/gif",
        "psd" | "psb" => "image/vnd.adobe.photoshop",
        _ => "application/octet-stream",
    }
}
