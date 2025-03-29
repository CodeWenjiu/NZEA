BUILD_DIR = ./build
OBJ_DIR = $(BUILD_DIR)/obj_dir

VSRC_DIR = ./vsrc/
DESIGN_FILE ?= $(abspath $(VSRC_DIR))/top.sv

VERILATOR = verilator
VERILATOR_CFLAGS += -j `nproc` -MMD --build -cc  \
				-O3 --x-assign fast --x-initial fast --noassert  \
				--trace --trace-fst 

VERILATOR_SIMFLAGS += --timescale "1ns/1ns" --no-timing --top-module top

VERILATOR_COMPILEFLAGS += $(DESIGN_FILE) \
				--Mdir $(OBJ_DIR) \
				--lib-create nzea

generate:
	@mkdir -p $(OBJ_DIR)
	ccache $(VERILATOR) $(VERILATOR_CFLAGS) $(VERILATOR_COMPILEFLAGS) $(VERILATOR_SIMFLAGS)
	@echo "Verilator simulation files generated in $(OBJ_DIR)"

clean:
	@rm -rf $(BUILD_DIR)
	@echo "Cleaned up $(OBJ_DIR)"

.PHONY: generate clean
