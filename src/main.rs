use dlopen2::wrapper::{Container, WrapperApi};

#[derive(WrapperApi)]
struct Api<'a> {
    // A function or field may not always exist in the library.
    example_c_fun_option: Option<unsafe extern "C" fn()>,
    example_reference_option:  Option<&'a mut i32>,
}

fn main() {
    let mut cont: Container<Api> =
        unsafe { Container::load("./build/obj_dir/libnzea.so") }.expect("Could not open library or load symbols");
    
    // Optional functions return Some(result) if the function is present or None if absent.
    unsafe{ cont.example_c_fun_option() };
    // Optional fields are Some(value) if present and None if absent.
    if let Some(example_reference) = &mut cont.example_reference_option {
        *example_reference = &mut 5;
    }
}
