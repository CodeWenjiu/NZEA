//! Raw serial port access: termios via `libc`, no serial crate, no system deps.

use std::fs::File;
use std::io::{self, Read, Write};
use std::os::fd::{AsRawFd, RawFd};

pub struct Serial {
    file: File,
    fd: RawFd,
}

impl Serial {
    /// Open the port read-write, flush stale input, and switch it to raw 8N1 at `baud`.
    pub fn open(path: &str, baud: u32) -> io::Result<Serial> {
        let file = File::options().read(true).write(true).open(path)?;
        let fd = file.as_raw_fd();
        set_raw_mode(fd, baud)?;
        unsafe { libc::tcflush(fd, libc::TCIOFLUSH) };
        Ok(Serial { file, fd })
    }

    pub fn write_all(&mut self, data: &[u8]) -> io::Result<()> {
        self.file.write_all(data)
    }

    pub fn flush(&mut self) -> io::Result<()> {
        self.file.flush()
    }

    /// Blocking read; returns as soon as at least one byte is available (VMIN=1).
    pub fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.file.read(buf)
    }

    /// Whether the fd is readable within `timeout_ms` (`-1` blocks indefinitely).
    pub fn wait_readable(&self, timeout_ms: i32) -> io::Result<bool> {
        let mut pfd = libc::pollfd {
            fd: self.fd,
            events: libc::POLLIN,
            revents: 0,
        };
        let r = unsafe { libc::poll(&mut pfd, 1, timeout_ms) };
        if r < 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(r > 0)
        }
    }
}

fn cfspeed(baud: u32) -> io::Result<libc::speed_t> {
    let s = match baud {
        9600 => libc::B9600,
        19200 => libc::B19200,
        38400 => libc::B38400,
        57600 => libc::B57600,
        115200 => libc::B115200,
        230400 => libc::B230400,
        460800 => libc::B460800,
        921600 => libc::B921600,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                format!("unsupported baud rate {baud}"),
            ))
        }
    };
    Ok(s as libc::speed_t)
}

fn set_raw_mode(fd: RawFd, baud: u32) -> io::Result<()> {
    let speed = cfspeed(baud)?;
    let mut tio: libc::termios = unsafe { std::mem::zeroed() };
    if unsafe { libc::tcgetattr(fd, &mut tio) } != 0 {
        return Err(io::Error::last_os_error());
    }
    // cfmakeraw equivalent: no echo, no line buffering, no signal chars, 8N1.
    tio.c_iflag &= !(libc::IGNBRK
        | libc::BRKINT
        | libc::PARMRK
        | libc::ISTRIP
        | libc::INLCR
        | libc::IGNCR
        | libc::ICRNL
        | libc::IXON);
    tio.c_oflag &= !libc::OPOST;
    tio.c_lflag &= !(libc::ECHO | libc::ECHONL | libc::ICANON | libc::ISIG | libc::IEXTEN);
    tio.c_cflag &= !(libc::CSIZE | libc::PARENB);
    tio.c_cflag |= libc::CS8 | libc::CLOCAL | libc::CREAD;
    tio.c_cc[libc::VMIN] = 1;
    tio.c_cc[libc::VTIME] = 0;
    if unsafe { libc::cfsetispeed(&mut tio, speed) } != 0
        || unsafe { libc::cfsetospeed(&mut tio, speed) } != 0
    {
        return Err(io::Error::last_os_error());
    }
    if unsafe { libc::tcsetattr(fd, libc::TCSANOW, &tio) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}
