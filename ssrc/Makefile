PRJ = playground

PLATFORM ?= npc
BUILD_DIR = ../build/$(PLATFORM)

verilog:
	@mkdir -p $(BUILD_DIR)
	@mill -i nzea.runMain config.Elaborate$(PLATFORM) --target-dir $(BUILD_DIR)
	@DESIGN_FILE=$$(find $(BUILD_DIR) -maxdepth 1 -name "*.sv" ! -name "top.sv"); \
	if [ -n "$$DESIGN_FILE" ]; then \
		echo "Renaming $$DESIGN_BASENAME to top.sv"; \
		echo "Found design file: $$DESIGN_FILE"; \
		DESIGN_BASENAME=$$(basename "$$DESIGN_FILE"); \
		echo "Renaming $$DESIGN_BASENAME to top.sv"; \
		mv "$$DESIGN_FILE" "$(BUILD_DIR)/top.sv"; \
	fi;
	@DESIGN_FILE="$(BUILD_DIR)/top.sv"; \
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $$DESIGN_FILE; \
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $$DESIGN_FILE;
