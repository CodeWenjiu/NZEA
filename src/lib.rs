remu_macro::mod_flat!(api);

#[test]
fn test() {
    let top = Top::new();
    
    top.reset(10);

    top.cycle(10);
}
