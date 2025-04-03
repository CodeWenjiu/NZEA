BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir
BIN = $(BUILD_DIR)/libnzea.so

WRAPPER_DIR = ./wrapper/
CSRC = $(shell find $(abspath $(WRAPPER_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")
HSRC = $(shell find $(abspath $(WRAPPER_DIR)) -name "*.h" -or -name "*.hpp")

DESIGN_FILE ?= build/top.sv

VERILATOR = verilator
VERILATOR_CFLAGS += -j `nproc` -MMD --build -cc  \
				-O3 --x-assign fast --x-initial fast --noassert  \
				--trace --trace-fst 

VERILATOR_COMPILEFLAGS += $(DESIGN_FILE) \
				$(CSRC) \
				--Mdir $(OBJ_DIR) \
				--lib-create nzea

VERILATOR_SIMFLAGS += --timescale "1ns/1ns" --no-timing --top-module top

default: generate

generate: $(BIN)

$(BIN): $(CSRC) $(HSRC) $(VSRC) $(DESIGN_FILE)
	@mkdir -p $(OBJ_DIR)
	@ccache $(VERILATOR) $(VERILATOR_CFLAGS) $(VERILATOR_COMPILEFLAGS) $(VERILATOR_SIMFLAGS)
	@echo "Verilator simulation files generated in $(OBJ_DIR)"

clean:
	@rm -rf $(BUILD_DIR)
	@echo "Cleaned up $(BUILD_DIR)"

clean_obj:
	@rm -rf $(OBJ_DIR)
	@echo "Cleaned up $(OBJ_DIR)"

.PHONY: default generate clean clean_obj
