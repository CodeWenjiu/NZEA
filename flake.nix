{
  description = "Flake configuration for nzea";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
    rust-overlay.url = "github:oxalica/rust-overlay";
    rust-overlay.inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs =
    {
      nixpkgs,
      utils,
      rust-overlay,
      ...
    }:
    utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          overlays = [ (import rust-overlay) ];
        };

        # icsprout55 PDK (ysyx branch for yosys-sta compatibility)
        icsprout55 = pkgs.fetchFromGitHub {
          owner = "openecos-projects";
          repo = "icsprout55-pdk";
          rev = "ysyx";
          sha256 = "sha256-G7rYBZQ9l6kFy+u3Q1lvPbps/VJ6Oogv2+gJjRW+KYI=";
        };
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            jdk
            mill
            scalafmt
            yosys
            ieda
            circt # firtool for Chisel Verilog generation

            verilator # for chisel svsim
            iverilog # for 4-state simulation
            veridian

            surfer

            (rust-bin.nightly.latest.default.override {
              extensions = [
                "rust-src"
                "clippy"
                "rust-analyzer"
                "llvm-tools-preview"
              ];
            })

            # === FPGA Toolchain ===
            yosys # RTL synthesis (synth_gowin)
            nextpnr # P&R (nextpnr-himbaechel with Gowin uarch)
            python3Packages.apycula # gowin_pack, gowin_bba
            openfpgaloader # Bitstream flashing via USB-C
          ];

          PDK_PATH = icsprout55;
          CHISEL_FIRTOOL_PATH = "${pkgs.circt}/bin";
        };
      }
    );
}
