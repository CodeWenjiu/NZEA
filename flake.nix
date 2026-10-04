{
  description = "Flake configuration for nzea";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
    rust-overlay.url = "github:oxalica/rust-overlay";
    rust-overlay.inputs.nixpkgs.follows = "nixpkgs";

    # Scala 3 / zaozi toolchain for the core. The submodule at ./.zaozi holds the
    # sources we publish locally (`just z-publish`); keep its commit in sync with
    # this pin.
    zaozi.url = "github:CodeWenjiu/zaozi";
  };

  outputs =
    {
      nixpkgs,
      utils,
      rust-overlay,
      zaozi,
      ...
    }:
    utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ (import rust-overlay) ];
        };

        # zaozi's pins (JDK 25, Mill, CIRCT/MLIR with shared libraries, jextract).
        zpkgs = zaozi.legacyPackages.${system};

        # icsprout55 PDK (ysyx branch for yosys-sta compatibility)
        icsprout55 = pkgs.fetchFromGitHub {
          owner = "openecos-projects";
          repo = "icsprout55-pdk";
          rev = "ysyx";
          sha256 = "sha256-G7rYBZQ9l6kFy+u3Q1lvPbps/VJ6Oogv2+gJjRW+KYI=";
        };

        # icsprout55 SRAM macro: a single-port 1RW 8192x32 (32 KiB) instance from the
        # ECOS Factory compiler, shipped as a release asset of the PDK's sibling repo.
        # Unpacked so the synthesis flow reads its Verilog (behavioural model and
        # stub) and Liberty directly.
        ics55Sram = pkgs.runCommand "ics55_ecos_sram_8192x32_m16" { } ''
          mkdir -p $out
          tar xzf ${
            pkgs.fetchurl {
              url = "https://github.com/openecos-projects/ics55_ecos_sram/releases/download/sram-v1-c7d505223a5a2d71fd3a6dc86704bb8fff55e82bfb8fa63abcaa3d2d48b14830/ics55_ecos_sram_8192x32_m16.tar.gz";
              hash = "sha256-pDdox8pDR3s07WZXHq6/VwxDbdOKEzciE7AvVoryf1k=";
            }
          } -C $out --strip-components=1
        '';
      in
      {
        devShells.default = pkgs.mkShell {
          packages = [
            # === Scala 3 / zaozi ===
            zpkgs.jdk25
            zpkgs.mill
            zpkgs.scalafmt
            zpkgs.circt-install # firtool + libCIRCT
            zpkgs.mlir-install # libMLIR
            zpkgs.jextract
            zpkgs.lit
            zpkgs.z3
            zpkgs.scala-cli

            # === RTL simulation / analysis ===
            pkgs.iverilog # 4-state simulation
            pkgs.verilator
            pkgs.veridian
            pkgs.surfer

            # === Synthesis / STA ===
            pkgs.yosys
            pkgs.ieda

            (pkgs.rust-bin.nightly.latest.default.override {
              extensions = [
                "rust-src"
                "clippy"
                "rust-analyzer"
                "llvm-tools-preview"
              ];
            })
            pkgs.sccache

            # === FPGA toolchain ===
            pkgs.nextpnr # P&R (nextpnr-himbaechel with Gowin uarch)
            pkgs.nextpnr-xilinx # P&R (nextpnr-xilinx, retained for future use)
            pkgs.python3Packages.apycula # gowin_pack, gowin_bba
            pkgs.uv # python package manager
            pkgs.openfpgaloader # Bitstream flashing via USB-C
          ];

          env = {
            PDK_PATH = icsprout55;
            SRAM_PATH = ics55Sram;

            CIRCT_INSTALL_PATH = "${zpkgs.circt-install}";
            MLIR_INSTALL_PATH = "${zpkgs.mlir-install}";
            JEXTRACT_INSTALL_PATH = "${zpkgs.jextract}";
            LIBC_INCLUDE_PATH = "${zpkgs.stdenv.cc.libc.dev}/include";
            LIT_INSTALL_PATH = "${zpkgs.lit}";
            Z3_LIB = "${zpkgs.z3.lib}/lib/libz3.so";
            # Share Mill outputs with the BSP so the language server sees SemanticDB.
            MILL_NO_SEPARATE_BSP_OUTPUT_DIR = "1";
          };
        };
      }
    );
}
