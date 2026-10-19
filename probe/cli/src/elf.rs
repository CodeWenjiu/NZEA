//! ELF32 images for the boot loader: the file-backed bytes of an executable's `PT_LOAD` segments, as
//! the one contiguous word run the loader writes.
//!
//! The ELF is read with `object` — the reader remu's own image loader uses, so both places see a
//! program the same way (a segment's address is its `p_vaddr`, its bytes are the file-backed ones).
//!
//! The loader writes a frame's words from one address up (`protocol::load_frame`), and the board holds
//! the core for exactly one frame — so a program has to arrive as one image. The bytes between
//! segments (alignment padding) are written as zeros: they belong to no segment, and writing them is
//! what makes the run contiguous.
//!
//! Only the *file-backed* bytes are written. A segment's `p_memsz` tail (`.bss`, heap, stack) is not:
//! the platform's images carry all of it (128 MiB of it in remu's apps) and nothing initializes it —
//! the program's own startup is what clears what it needs, exactly as it does under remu's loader,
//! which also writes only the segment's file bytes.

use object::{Object as _, ObjectSegment as _};

/// The most padding (bytes belonging to no segment) an image may be spread over: the loader writes
/// every word of the run, so a spread-out image is refused instead of sent as megabytes of zeros.
const MAX_PADDING: usize = 256 << 10;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Segment {
    pub addr: u32,
    pub file_bytes: u32,
}

#[derive(Debug)]
pub struct Image {
    /// The program's entry point, as the ELF states it (the board starts at its own reset vector).
    pub entry: u32,
    /// Where the run begins: the lowest segment address.
    pub base: u32,
    /// The run's bytes, gaps zero-filled, padded to whole words.
    pub bytes: Vec<u8>,
    pub segments: Vec<Segment>,
    /// Segments with no file bytes (`.bss`, heap, stack): nothing to write.
    pub empty_segments: usize,
    /// Bytes written between segments (alignment padding).
    pub padding_bytes: usize,
}

impl Image {
    /// The run as the loader's words, little-endian.
    pub fn words(&self) -> Vec<u32> {
        self.bytes
            .as_chunks::<4>()
            .0
            .iter()
            .map(|c| u32::from_le_bytes(*c))
            .collect()
    }
}

pub fn is_elf(data: &[u8]) -> bool {
    data.starts_with(b"\x7fELF")
}

pub fn parse(data: &[u8]) -> Result<Image, String> {
    let file = object::File::parse(data).map_err(|e| format!("not an ELF image: {e}"))?;
    if file.is_64() {
        return Err("not an ELF32 image (the board is riscv32)".to_string());
    }
    if !file.is_little_endian() {
        return Err("not a little-endian image".to_string());
    }
    if file.architecture() != object::Architecture::Riscv32 {
        return Err(format!(
            "an image for {:?}, not riscv32",
            file.architecture()
        ));
    }
    let entry = u32::try_from(file.entry()).map_err(|_| {
        format!(
            "an entry point at {:#x}, outside the board's addresses",
            file.entry()
        )
    })?;

    let mut loaded: Vec<(u32, &[u8])> = Vec::new();
    let mut empty_segments = 0usize;
    for segment in file.segments() {
        let addr = u32::try_from(segment.address()).map_err(|_| {
            format!(
                "a segment at {:#x}, outside the board's addresses",
                segment.address()
            )
        })?;
        let bytes = segment
            .data()
            .map_err(|e| format!("the segment at {addr:#010x}: {e}"))?;
        if bytes.is_empty() {
            if segment.size() > 0 {
                empty_segments += 1;
            }
            continue;
        }
        if addr % 4 != 0 {
            return Err(format!(
                "the segment at {addr:#010x} is not word-aligned (the loader writes whole words)"
            ));
        }
        loaded.push((addr, bytes));
    }
    if loaded.is_empty() {
        return Err("no segment with file bytes".to_string());
    }

    loaded.sort_by_key(|(addr, _)| *addr);
    for pair in loaded.windows(2) {
        let (a, a_bytes) = pair[0];
        let (b, _) = pair[1];
        if a as u64 + a_bytes.len() as u64 > b as u64 {
            return Err(format!("the segments at {a:#010x} and {b:#010x} overlap"));
        }
    }

    let base = loaded[0].0;
    let (last, last_bytes) = *loaded.last().expect("at least one segment");
    let span = (last - base) as usize + last_bytes.len();
    let payload_bytes: usize = loaded.iter().map(|(_, bytes)| bytes.len()).sum();
    let padding_bytes = span - payload_bytes;
    if padding_bytes > MAX_PADDING {
        return Err(format!(
            "the image's segments are spread over {span} bytes with {padding_bytes} bytes of padding; \
             the loader writes one contiguous run — link the program as one region"
        ));
    }

    let mut bytes = vec![0u8; span.next_multiple_of(4)];
    for (addr, segment) in &loaded {
        let at = (*addr - base) as usize;
        bytes[at..at + segment.len()].copy_from_slice(segment);
    }

    Ok(Image {
        entry,
        base,
        bytes,
        segments: loaded
            .iter()
            .map(|(addr, bytes)| Segment {
                addr: *addr,
                file_bytes: bytes.len() as u32,
            })
            .collect(),
        empty_segments,
        padding_bytes,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    /// An ELF32 for RISC-V with one `PT_LOAD` per entry: `(addr, file bytes, mem bytes)`.
    fn elf_with(entry: u32, segs: &[(u32, Vec<u8>, u32)]) -> Vec<u8> {
        let phoff = 52u32;
        let phentsize = 32u32;
        let mut out = vec![0u8; phoff as usize];
        out[..4].copy_from_slice(b"\x7fELF");
        out[4] = 1; // ELFCLASS32
        out[5] = 1; // ELFDATA2LSB
        out[6] = 1; // EV_CURRENT
        out[16..18].copy_from_slice(&2u16.to_le_bytes()); // e_type: ET_EXEC
        out[18..20].copy_from_slice(&243u16.to_le_bytes()); // EM_RISCV
        out[20..24].copy_from_slice(&1u32.to_le_bytes()); // e_version
        out[24..28].copy_from_slice(&entry.to_le_bytes());
        out[28..32].copy_from_slice(&phoff.to_le_bytes());
        out[40..42].copy_from_slice(&52u16.to_le_bytes());
        out[42..44].copy_from_slice(&(phentsize as u16).to_le_bytes());
        out[44..46].copy_from_slice(&(segs.len() as u16).to_le_bytes());

        let mut off = phoff + phentsize * segs.len() as u32;
        for (addr, bytes, mem) in segs {
            let mut ph = vec![0u8; 32];
            ph[0..4].copy_from_slice(&1u32.to_le_bytes()); // p_type: PT_LOAD
            ph[4..8].copy_from_slice(&off.to_le_bytes());
            ph[8..12].copy_from_slice(&addr.to_le_bytes());
            ph[12..16].copy_from_slice(&addr.to_le_bytes());
            ph[16..20].copy_from_slice(&(bytes.len() as u32).to_le_bytes());
            ph[20..24].copy_from_slice(&mem.to_le_bytes());
            out.extend_from_slice(&ph);
            off += bytes.len() as u32;
        }
        for (_, bytes, _) in segs {
            out.extend_from_slice(bytes);
        }
        out
    }

    #[test]
    fn touching_segments_become_one_run() {
        let data = elf_with(
            0x8000_0000,
            &[
                (0x8000_0000, vec![1, 2, 3, 4], 4),
                (0x8000_0004, vec![5, 6, 7, 8], 4),
            ],
        );
        let image = parse(&data).expect("parses");
        assert_eq!(image.base, 0x8000_0000);
        assert_eq!(image.bytes, vec![1, 2, 3, 4, 5, 6, 7, 8]);
        assert_eq!(image.words(), vec![0x0403_0201, 0x0807_0605]);
        assert_eq!(image.padding_bytes, 0);
        assert_eq!(image.empty_segments, 0);
        assert_eq!(image.entry, 0x8000_0000);
    }

    #[test]
    fn a_gap_between_segments_is_zero_filled_and_counted() {
        let data = elf_with(
            0x8000_0000,
            &[
                (0x8000_0000, vec![0xAA; 8], 8),
                (0x8000_0010, vec![0xBB; 4], 4),
            ],
        );
        let image = parse(&data).expect("parses");
        assert_eq!(image.bytes.len(), 20);
        assert_eq!(image.bytes[8..16], [0u8; 8]);
        assert_eq!(image.padding_bytes, 8);
    }

    #[test]
    fn a_segment_without_file_bytes_is_not_written() {
        // `.bss`/heap/stack: mem bytes but nothing in the file (remu's apps carry 128 MiB of this).
        let data = elf_with(
            0x8000_0000,
            &[
                (0x8000_0000, vec![1, 2, 3, 4], 4),
                (0x8000_0004, vec![], 1 << 20),
            ],
        );
        let image = parse(&data).expect("parses");
        assert_eq!(image.bytes, vec![1, 2, 3, 4]);
        assert_eq!(image.empty_segments, 1);
        assert_eq!(image.segments.len(), 1);
    }

    #[test]
    fn a_spread_out_image_is_refused() {
        let data = elf_with(
            0x8000_0000,
            &[(0x8000_0000, vec![0; 4], 4), (0x8080_0000, vec![0; 4], 4)],
        );
        let err = parse(&data).expect_err("refused");
        assert!(err.contains("padding"), "{err}");
    }

    #[test]
    fn bad_images_are_refused() {
        let good = elf_with(0x8000_0000, &[(0x8000_0000, vec![0; 4], 4)]);
        assert!(parse(&[]).is_err());
        assert!(parse(b"not an elf at all, really").is_err());

        let mut data = good.clone();
        data[4] = 2; // ELFCLASS64
        assert!(parse(&data).is_err());

        data = good.clone();
        data[5] = 2; // big-endian: refused here, or already by the reader
        assert!(parse(&data).is_err());

        data = good;
        data[18] = 0x03; // EM_386
        assert!(parse(&data).unwrap_err().contains("riscv32"));
    }

    #[test]
    fn a_misaligned_or_overlapping_segment_is_refused() {
        let data = elf_with(0x8000_0000, &[(0x8000_0002, vec![0; 4], 4)]);
        assert!(parse(&data).unwrap_err().contains("word-aligned"));

        let data = elf_with(
            0x8000_0000,
            &[(0x8000_0000, vec![0; 8], 8), (0x8000_0004, vec![0; 4], 4)],
        );
        assert!(parse(&data).unwrap_err().contains("overlap"));
    }
}
