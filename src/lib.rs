remu_macro::mod_flat!(api);

#[test]
fn test() {
    let top = Top::new();

    let callbacks = <Callbacks as Default>::default();
    
    top.init(callbacks);

    top.reset(10);

    top.cycle(10);
}
