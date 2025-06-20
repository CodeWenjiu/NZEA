use std::{env, path::PathBuf};

remu_macro::mod_flat!(api, simulator);

pub fn get_nzea_root() -> PathBuf {
    let exec_dir = env::current_dir().unwrap();
    let project_root = exec_dir
        .ancestors()
        .find(|p| p.join("simulator").exists())
        .unwrap();

    project_root
        .join("simulator/src/nzea")
}
