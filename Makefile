default: generate

PLATFORM ?= ysyxsoc
ROOT_BUILD_DIR = ./build
BUILD_DIR = $(ROOT_BUILD_DIR)/$(PLATFORM)
OBJ_DIR = $(BUILD_DIR)/obj_dir
LIB = $(BUILD_DIR)/libnzea.so

# 8< -------- lib c -------- 8< #

WRAPPER_DIR = ./wrapper

NXDC_FILES = $(abspath $(WRAPPER_DIR)/constr/$(PLATFORM).nxdc)
SRC_AUTO_BIND = $(abspath $(BUILD_DIR)/auto_bind.cpp)
$(SRC_AUTO_BIND): $(NXDC_FILES)
	@mkdir -p $(OBJ_DIR)
	python3 nvboard/scripts/auto_pin_bind.py $^ $@
CSRC = $(shell find $(abspath $(WRAPPER_DIR)) -name "*.c" -or -name "*.cc" -or -name "*.cpp")
CSRC += $(SRC_AUTO_BIND)

INC_PATH ?=
INCFLAGS = $(addprefix -I, $(INC_PATH))

HSRC += $(INCFLAGS)

# 8< -------- scala -------- 8< #

SSRC_DIR = ./ssrc
SSRC_HDL_DIR = $(SSRC_DIR)/HDL
SSRC_Elaborate_DIR = $(SSRC_DIR)/Elaborate
SSRC = $(shell find $(abspath $(SSRC_HDL_DIR)) -name "*.scala")
SSRC += $(shell find $(abspath $(SSRC_Elaborate_DIR)) -name "*.scala")
SSRC += $(SSRC_DIR)/build.sc

# 8< -------- verilator -------- 8< #

SIM_DESIGN_FILE ?= $(BUILD_DIR)/top.sv
TOP_NAME = top

CFLAGS += $(addprefix -CFLAGS , $(HSRC))

verilog: $(SIM_DESIGN_FILE)

$(SIM_DESIGN_FILE): $(SSRC)
	$(MAKE) -C $(SSRC_DIR) verilog PLATFORM=$(PLATFORM)

VSRC_DIR = vsrc/$(PLATFORM)
VSRC_INCLUDE_DIR = $(VSRC_DIR)/include
VSRC += $(shell find $(abspath $(VSRC_DIR)/perip) -name "*.v" -or -name "*.sv")
VSRC += $(shell find $(abspath $(VSRC_DIR)/build) -name "*.v" -or -name "*.sv")
VSRC += $(shell find $(abspath $(VSRC_DIR)/vsrc) -name "*.v" -or -name "*.sv")
VSRC += $(SIM_DESIGN_FILE)

VERILATOR = verilator
VERILATOR_CFLAGS += -j `nproc` -MMD --build -cc  \
				-O3 --x-assign fast --x-initial fast --noassert  \
				--trace --trace-fst 

VERILATOR_SIMFLAGS += --timescale "1ns/1ns" --no-timing --top-module $(TOP_NAME)

generate: $(LIB)

NVBOARD_HOME_PATH = $(shell pwd)/nvboard

include $(NVBOARD_HOME_PATH)/scripts/nvboard.mk

VERILATOR_COMPILEFLAGS += $(VSRC) \
				-y $(VSRC_INCLUDE_DIR) \
				$(CSRC) \
				--Mdir $(OBJ_DIR) \
				--lib-create nzea

LDFLAGS = -L$(NVBOARD_ARCHIVE)

$(LIB): $(CSRC) $(VSRC) $(NVBOARD_ARCHIVE)
	@mkdir -p $(OBJ_DIR)
	@ccache $(VERILATOR) $(VERILATOR_CFLAGS) \
		$(CFLAGS) \
		$(VERILATOR_COMPILEFLAGS) \
		$(addprefix -LDFLAGS , $(LDFLAGS)) \
		$(VERILATOR_SIMFLAGS)
	@g++ -o $(OBJ_DIR)/libnzea.so -shared -Wl,--whole-archive $(OBJ_DIR)/libnzea.a -Wl,--no-whole-archive $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs) -lSDL2_image -lSDL2_ttf
	@echo "Verilator simulation files generated in $(OBJ_DIR)"

# 8< -------- yosys -------- 8< #

IMPL_DIR = $(BUILD_DIR)_core

IMPL_DESIGN_FILE ?= $(abspath $(IMPL_DIR)/top.sv)
RPT_FILE = $(IMPL_DIR)/result/top.rpt
STAT_FILE = $(IMPL_DIR)/result/synth_stat.txt

SDC_FILE = $(abspath $(WRAPPER_DIR)/sdc/$(PLATFORM).sdc)

$(IMPL_DESIGN_FILE): $(SSRC)
	$(MAKE) -C $(SSRC_DIR) verilog PLATFORM=$(PLATFORM)_core
	@sed -i 's/\bmodule\b/(\* keep_hierarchy ="yes" \*)\nmodule/g' $(IMPL_DESIGN_FILE)

$(RPT_FILE) $(STAT_FILE): $(IMPL_DESIGN_FILE)
	$(MAKE) syn

syn: $(IMPL_DESIGN_FILE)
	$(MAKE) -C $(YOSYS_HOME) sta \
		DESIGN=top SDC_FILE=$(SDC_FILE) \
		RTL_FILES=$(IMPL_DESIGN_FILE) \
		CLK_FREQ_MHZ=500 \
		RESULT_DIR=$(abspath $(IMPL_DIR)/result)

clean:
	@rm -rf $(ROOT_BUILD_DIR)
	@rm -rf $(ROOT_IMPL_DIR)
	@echo "Cleaned up"

clean_pla:
	@rm -rf $(BUILD_DIR)
	@echo "Cleaned up $(BUILD_DIR)"

clean_obj:
	@rm -rf $(OBJ_DIR)
	@echo "Cleaned up $(OBJ_DIR)"

.PHONY: default generate clean clean_pla clean_obj impl
