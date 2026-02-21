_default:
    @just --list

# Initialize Project
init:
    @mill mill.bsp.BSP/install

# Clean ALL
clean-all: clean
    @mill mill clean

clean:
    @rm -rf build
