default: generate

PLATFORM ?= jyd
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
SSRC_PLATFORM_DIR = $(SSRC_DIR)/platform
SSRC = $(shell find $(abspath $(SSRC_HDL_DIR)) -name "*.scala")
SSRC += $(shell find $(abspath $(SSRC_PLATFORM_DIR)) -name "*.scala")
SSRC += $(SSRC_DIR)/build.sc

# 8< -------- verilator -------- 8< #

DESIGN_FILE ?= $(BUILD_DIR)/top.sv

verilog: $(DESIGN_FILE)

$(DESIGN_FILE): $(SSRC)
	$(MAKE) -C $(SSRC_DIR) verilog PLATFORM=$(PLATFORM)

VSRC_DIR = vsrc/$(PLATFORM)
VSRC_INCLUDE_DIR = $(VSRC_DIR)/include
VSRC += $(shell find $(abspath $(VSRC_DIR)/perip) -name "*.v" -or -name "*.sv")
VSRC += $(shell find $(abspath $(VSRC_DIR)/build) -name "*.v" -or -name "*.sv")
VSRC += $(DESIGN_FILE)

VERILATOR = verilator
VERILATOR_CFLAGS += -j `nproc` -MMD --build -cc  \
				-O3 --x-assign fast --x-initial fast --noassert  \
				--trace --trace-fst 

VERILATOR_SIMFLAGS += --timescale "1ns/1ns" --no-timing --top-module top

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
		$(addprefix -CFLAGS , $(HSRC)) \
		$(VERILATOR_COMPILEFLAGS) \
		$(addprefix -LDFLAGS , $(LDFLAGS)) \
		$(VERILATOR_SIMFLAGS)
	@g++ -o $(OBJ_DIR)/libnzea.so -shared -Wl,--whole-archive $(OBJ_DIR)/libnzea.a -Wl,--no-whole-archive $(NVBOARD_ARCHIVE) $(shell sdl2-config --libs) -lSDL2_image -lSDL2_ttf
	@echo "Verilator simulation files generated in $(OBJ_DIR)"

clean:
	@rm -rf $(ROOT_BUILD_DIR)
	@echo "Cleaned up $(ROOT_BUILD_DIR)"

clean_pla:
	@rm -rf $(BUILD_DIR)
	@echo "Cleaned up $(BUILD_DIR)"

clean_obj:
	@rm -rf $(OBJ_DIR)
	@echo "Cleaned up $(OBJ_DIR)"

.PHONY: default generate clean clean_pla clean_obj
