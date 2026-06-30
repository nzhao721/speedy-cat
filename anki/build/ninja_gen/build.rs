// Copyright: Ankitects Pty Ltd and contributors
// License: GNU AGPL, version 3 or later; http://www.gnu.org/licenses/agpl.html

fn main() {
    #[cfg(windows)]
    {
        // Prevent Windows UAC "Installer Detection" from requiring elevation.
        // Windows auto-elevates executables whose names match installer heuristics
        // (install/update/setup/patch). An asInvoker manifest overrides this.
        //
        // Use manifest_optional() rather than manifest_required(): on a no-admin
        // dev box the Windows SDK *resource compiler* (rc.exe) may be absent
        // (e.g. when using a bundled headers/libs-only SDK), which makes
        // manifest_required().unwrap() panic and breaks the whole build. The
        // manifest is still embedded when rc.exe is available; when it isn't we
        // emit a warning and continue, since dev binaries (runner.exe) aren't
        // subject to installer-detection auto-elevation.
        if let Err(e) =
            embed_resource::compile("win/update-tools.rc", embed_resource::NONE).manifest_optional()
        {
            println!("cargo:warning=ninja_gen: update-tools manifest not embedded ({e})");
        }
    }
}
