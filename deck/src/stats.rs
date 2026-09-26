//! Live values buttons can show in their labels, e.g. "CPU {cpu}%". Sampled once a second.
//! Time and date come from the device's own clock instead, so they tick without the network.

use crate::app::App;
use serde_json::{Map, Value, json};
use std::sync::Arc;
use std::time::Duration;
use sysinfo::{CpuRefreshKind, MemoryRefreshKind, RefreshKind, System};

pub async fn run(app: Arc<App>) {
    let mut sys = System::new_with_specifics(
        RefreshKind::nothing()
            .with_cpu(CpuRefreshKind::nothing().with_cpu_usage())
            .with_memory(MemoryRefreshKind::nothing().with_ram()),
    );
    let host = crate::app::host_name();
    let mut tick = tokio::time::interval(Duration::from_secs(1));
    loop {
        tick.tick().await;
        sys.refresh_cpu_usage();
        sys.refresh_memory();
        let total = sys.total_memory().max(1) as f64;
        let used = sys.used_memory() as f64;
        let gb = 1024.0 * 1024.0 * 1024.0;
        let mut vars = Map::new();
        vars.insert("cpu".into(), json!(sys.global_cpu_usage().round() as u32));
        vars.insert("ram".into(), json!((used / total * 100.0).round() as u32));
        vars.insert("ram_used".into(), json!(format!("{:.1}", used / gb)));
        vars.insert("ram_total".into(), json!(format!("{:.0}", total / gb)));
        vars.insert("host".into(), Value::String(host.clone()));
        app.set_vars(vars);
    }
}
