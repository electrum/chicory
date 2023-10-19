const VOWELS: &[char] = &['a', 'A', 'e', 'E', 'i', 'I', 'o', 'O', 'u', 'U'];

use std::mem;
use std::slice;
use std::str;

#[no_mangle]
pub extern "C" fn alloc(len: i32) -> *const u8 {
    let mut buf = Vec::with_capacity(len as usize);
    let ptr = buf.as_mut_ptr();
    // tell Rust not to clean this up
    mem::forget(buf);
    ptr
}

// #[no_mangle]
// pub extern fn count(ptr: i32, len: i32) -> i32 {
//     let bytes = unsafe { slice::from_raw_parts(ptr as *const u8, len as usize) };
//     let s = str::from_utf8(bytes).unwrap();
//     let mut count: i32 = 0;
//     for ch in s.chars() {
//         if VOWELS.contains(&ch) {
//             count += 1;
//         }
//     }
//     count
// }

#[no_mangle]
pub extern fn count(ptr: i32, len: i32) -> i32 {
    let bytes = unsafe { slice::from_raw_parts(ptr as *const u8, len as usize) };
    let s = str::from_utf8(bytes).unwrap();
    let mut count: i32 = 0;
    for ch in s.chars() {
      count += 1;
    }
    count
}
