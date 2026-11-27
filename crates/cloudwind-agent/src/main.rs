mod inbox;
mod sync;

use std::{fs, path::PathBuf, time::UNIX_EPOCH};

use anyhow::{bail, Context, Result};
use clap::{Parser, Subcommand};
use cloudwind_core::{encrypt, LibraryKey};
use serde::{Deserialize, Serialize};

use inbox::DirInbox;

#[derive(Parser)]
#[command(name = "cloudwind", version, about = "Cloudwind PC agent")]
struct Cli {
    /// Config file (default: ~/.config/cloudwind/config.json)
    #[arg(long, global = true)]
    config: Option<PathBuf>,
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    /// Create a library key and config.
    Init {
        /// Folder where decrypted photos are written (your photo app watches this).
        #[arg(long)]
        library: PathBuf,
        /// Inbox location (local folder for now).
        #[arg(long)]
        inbox: PathBuf,
    },
    /// Pull new photos from the inbox into the library.
    Sync,
    /// Print the key for pairing the phone.
    Pair,
    /// Encrypt a file into the inbox, as the phone will (for testing).
    Upload { file: PathBuf },
}

#[derive(Serialize, Deserialize)]
struct Config {
    key: String,
    library: PathBuf,
    inbox: PathBuf,
}

fn config_path(cli: &Cli) -> Result<PathBuf> {
    if let Some(p) = &cli.config {
        return Ok(p.clone());
    }
    let home = std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE"));
    Ok(PathBuf::from(home.context("no home directory")?).join(".config/cloudwind/config.json"))
}

fn load(path: &PathBuf) -> Result<(Config, LibraryKey)> {
    let raw = fs::read_to_string(path)
        .with_context(|| format!("no config at {path:?}; run `cloudwind init` first"))?;
    let cfg: Config = serde_json::from_str(&raw)?;
    let key = LibraryKey::from_base64(&cfg.key)?;
    Ok((cfg, key))
}

fn main() -> Result<()> {
    let cli = Cli::parse();
    let path = config_path(&cli)?;
    match &cli.cmd {
        Cmd::Init { library, inbox } => {
            if path.exists() {
                bail!("{path:?} already exists; refusing to overwrite the key");
            }
            let key = LibraryKey::generate();
            let cfg = Config { key: key.to_base64(), library: library.clone(), inbox: inbox.clone() };
            fs::create_dir_all(path.parent().unwrap())?;
            write_private(&path, &serde_json::to_vec_pretty(&cfg)?)?;
            println!("Created {path:?}\nLibrary key (keep a copy somewhere safe): {}", cfg.key);
        }
        Cmd::Pair => println!("{}", load(&path)?.0.key),
        Cmd::Sync => {
            let (cfg, key) = load(&path)?;
            let r = sync::run(&DirInbox { root: cfg.inbox }, &key, &cfg.library)?;
            for p in &r.synced {
                println!("synced {}", p.display());
            }
            for (id, e) in &r.failed {
                eprintln!("FAILED {id}: {e}");
            }
            println!("{} synced, {} failed", r.synced.len(), r.failed.len());
            if !r.failed.is_empty() {
                std::process::exit(1);
            }
        }
        Cmd::Upload { file } => {
            let (cfg, key) = load(&path)?;
            let modified = fs::metadata(file)?
                .modified()?
                .duration_since(UNIX_EPOCH)
                .ok()
                .map(|d| d.as_secs());
            let name = file.file_name().context("no file name")?.to_string_lossy();
            let mut ct = Vec::new();
            encrypt(&key, &name, modified, || fs::File::open(file), &mut ct)?;
            let id = DirInbox { root: cfg.inbox }.put(&ct)?;
            println!("uploaded {name} as {id}");
        }
    }
    Ok(())
}

fn write_private(path: &PathBuf, data: &[u8]) -> Result<()> {
    #[cfg(unix)]
    {
        use std::{io::Write, os::unix::fs::OpenOptionsExt};
        let mut f = fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(path)?;
        f.write_all(data)?;
    }
    #[cfg(not(unix))]
    fs::write(path, data)?;
    Ok(())
}
