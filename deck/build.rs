// Gives the Windows .exe its icon and name. Other platforms need nothing here.
fn main() {
    println!("cargo:rerun-if-changed=assets/dotdeck.ico");
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("windows") {
        let mut res = winresource::WindowsResource::new();
        res.set_icon("assets/dotdeck.ico");
        res.set("ProductName", "Dotdeck");
        res.set("FileDescription", "Dotdeck");
        if let Err(e) = res.compile() {
            println!("cargo:warning=built without the Windows icon: {e}");
        }
    }
}
