PRJ = playground

PLATFORM ?= npc
BUILD_DIR = ../build/$(PLATFORM)
DESIGN_FILE ?= $(shell find $(abspath $(BUILD_DIR)) -name "*.sv")

verilog:
	@mkdir -p $(BUILD_DIR)
	mill -i nzea.runMain config.Elaborate$(PLATFORM) --target-dir $(BUILD_DIR)
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $(DESIGN_FILE)
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $(DESIGN_FILE)
