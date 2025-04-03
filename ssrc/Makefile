PRJ = playground

BUILD_DIR = ../build
DESIGN_FILE ?= $(abspath $(BUILD_DIR))/top.sv

verilog:
	@mkdir -p $(BUILD_DIR)
	mill -i nzea.runMain config.Elaboratenpc --target-dir $(BUILD_DIR)
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $(DESIGN_FILE)
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $(DESIGN_FILE)
