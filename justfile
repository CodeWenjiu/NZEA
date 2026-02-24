_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Run nzea_cli with optional arguments (e.g. just run -w 64 -o out)
run *ARGS:
    @mill nzea_cli.run {{ ARGS }}

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
