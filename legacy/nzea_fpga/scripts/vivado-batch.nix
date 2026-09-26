# Batch (headless) Vivado FHS environment.
#
# A copy of the Xilinx FHS env (/mnt/data/tools/Xilinx/shell.nix) with the GUI
# launch stripped from the profile. The original profile ends with a bare
# `vivado` that starts the GUI and blocks, so it cannot be used for batch flows.
# This file keeps the full library set (Vivado needs them at runtime) but only
# sets up PATH/library env vars, letting `just vivado-build` / `just vivado-program`
# drive Vivado headlessly.
#
# Enter it and run commands via stdin, e.g.:
#   nix-shell nzea_fpga/scripts/vivado-batch.nix <<'EOF'
#   vivado -mode batch -source build_all.tcl -nolog -nojournal
#   exit
#   EOF
{
  pkgs ? import <nixpkgs> { },
}:

(pkgs.buildFHSEnv {
  name = "vivado-fhs-env";

  targetPkgs =
    pkgs: with pkgs; [
      # Core system libraries
      libxcrypt-legacy
      stdenv.cc.cc
      stdenv.cc.cc.lib
      glibc
      zlib
      libuuid

      # Use ncurses5 to avoid the tic.pc issue
      ncurses5

      # X11 and graphics (enhanced for Vivado compatibility)
      xorg.libX11
      xorg.libXext
      xorg.libXrender
      xorg.libXtst
      xorg.libXi
      xorg.libXft
      xorg.libxcb
      xorg.libXrandr
      xorg.libXinerama
      xorg.libXcursor
      xorg.libXmu
      xorg.libXpm
      xorg.libXaw
      xorg.libXt
      xorg.libSM
      xorg.libICE
      xorg.libXcomposite
      xorg.libXdamage
      xorg.libXfixes
      xorg.libXv
      xorg.libXvMC

      # OpenGL (enhanced)
      libGL
      libGLU
      mesa

      # Font and graphics (enhanced)
      freetype
      fontconfig
      cairo
      pango
      harfbuzz
      gdk-pixbuf
      atk
      pixman

      # GUI toolkits
      glib
      gtk2
      gtk3
      qt5.qtbase
      qt5.qtx11extras

      # Development tools
      gcc
      binutils

      # System utilities
      bash
      coreutils
      findutils
      gnugrep
      gnused
      gawk
      which
      file
      unzip
      nettools
      procps

      # Additional libraries that Vivado might need
      libpng
      libjpeg
      libtiff
      expat
      dbus
      systemd

      # Java and graphics compatibility
      xorg.xhost
      xorg.xauth
      xorg.xset
      xorg.xdpyinfo
    ];

  multiPkgs =
    pkgs: [
      # 32-bit compatibility libraries if needed
    ];

  runScript = "bash";

  profile = ''
    # Batch mode: no GUI launch. Set up PATH/lib paths only.
    export VIVADO_ROOT="/mnt/data/tools/Xilinx/Vivado/2023.2"
    export PATH="$VIVADO_ROOT/bin:$PATH"
    export XILINX_VIVADO="$VIVADO_ROOT"
    export LD_LIBRARY_PATH="/usr/lib:/usr/lib64:/lib:/lib64:$LD_LIBRARY_PATH"
    export LIBRARY_PATH="/usr/lib:/usr/lib64:/lib:/lib64:$LIBRARY_PATH"
    unset WAYLAND_DISPLAY
    export XILINX_LOCAL_USER_DATA="no"
  '';
}).env
