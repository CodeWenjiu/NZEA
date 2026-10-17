//! BootFsm UART boot protocol framing.
//!
//! Wire format (all little-endian):
//!   `magic u32 (0xB007B007) + addr u32 + size u32 + size * u32 data words`
//!
//! `size = 0` is the reset-only command: the FPGA holds the CPU in reset for the
//! 12-byte header and releases it without writing RAM, restarting the program that
//! is already resident in memory.

pub const MAGIC: u32 = 0xB007_B007;

pub fn load_frame(addr: u32, words: &[u32]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(12 + words.len() * 4);
    frame.extend_from_slice(&MAGIC.to_le_bytes());
    frame.extend_from_slice(&addr.to_le_bytes());
    frame.extend_from_slice(&(words.len() as u32).to_le_bytes());
    for w in words {
        frame.extend_from_slice(&w.to_le_bytes());
    }
    frame
}

pub fn reset_frame(addr: u32) -> Vec<u8> {
    let mut frame = Vec::with_capacity(12);
    frame.extend_from_slice(&MAGIC.to_le_bytes());
    frame.extend_from_slice(&addr.to_le_bytes());
    frame.extend_from_slice(&0u32.to_le_bytes());
    frame
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn load_frame_is_magic_addr_size_data_le() {
        let f = load_frame(0x0000_0002, &[0xDEAD_BEEF, 0x0000_0013]);
        assert_eq!(
            f,
            vec![
                0x07, 0xB0, 0x07, 0xB0, // magic
                0x02, 0x00, 0x00, 0x00, // addr
                0x02, 0x00, 0x00, 0x00, // size
                0xEF, 0xBE, 0xAD, 0xDE, // word 0
                0x13, 0x00, 0x00, 0x00, // word 1
            ]
        );
    }

    #[test]
    fn reset_frame_is_magic_addr_zero_size() {
        let f = reset_frame(0x0000_0010);
        assert_eq!(
            f,
            vec![
                0x07, 0xB0, 0x07, 0xB0, // magic
                0x10, 0x00, 0x00, 0x00, // addr
                0x00, 0x00, 0x00, 0x00, // size = 0 → reset-only
            ]
        );
    }
}
