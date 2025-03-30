BUILD_DIR = ./verilater_build
BIN = $(BUILD_DIR)/libnzea.so

WRAPPER_DIR = ./wrapper/
CSRC = $(shell find $(abspath $(WRAPPER_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")
HSRC = $(shell find $(abspath $(WRAPPER_DIR)) -name "*.h" -or -name "*.hpp")

VSRC_DIR = ./vsrc/
DESIGN_FILE ?= $(abspath $(VSRC_DIR))/top.sv

VERILATOR = verilator
VERILATOR_CFLAGS += -j `nproc` -MMD --build -cc  \
				-O3 --x-assign fast --x-initial fast --noassert  \
				--trace --trace-fst 

VERILATOR_SIMFLAGS += --timescale "1ns/1ns" --no-timing --top-module top

VERILATOR_COMPILEFLAGS += $(DESIGN_FILE) \
				$(CSRC) \
				--Mdir $(BUILD_DIR) \
				--lib-create nzea

default: generate

generate: $(BIN)

$(BIN): $(CSRC) $(HSRC) $(VSRC) $(DESIGN_FILE)
	@mkdir -p $(BUILD_DIR)
	@ccache $(VERILATOR) $(VERILATOR_CFLAGS) $(VERILATOR_COMPILEFLAGS) $(VERILATOR_SIMFLAGS)
	@echo "Verilator simulation files generated in $(BUILD_DIR)"

clean:
	@rm -rf $(BUILD_DIR)
	@echo "Cleaned up $(BUILD_DIR)"

.PHONY: default generate clean
