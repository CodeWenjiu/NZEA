PRJ = playground

PLATFORM ?= npc
BUILD_DIR = ../build/$(PLATFORM)

verilog:
	@mkdir -p $(BUILD_DIR)
	@mill -i nzea.runMain config.Elaborate$(PLATFORM) --target-dir $(BUILD_DIR)
	@DESIGN_FILE=$$(find $(BUILD_DIR) -maxdepth 1 -name "*.sv"); \
	if [ -z "$$DESIGN_FILE" ]; then \
		echo "Error: No .sv file found in $(BUILD_DIR) after mill command."; \
		exit 1; \
	fi; \
	echo "Found design file: $$DESIGN_FILE"; \
	sed -i -e 's/_\(aw\|ar\|w\|r\|b\)_\(\|bits_\)/_\1/g' $$DESIGN_FILE; \
	sed -i '/firrtl_black_box_resource_files.f/, $$d' $$DESIGN_FILE; \
	DESIGN_BASENAME=$$(basename "$$DESIGN_FILE"); \
	if [ "$$DESIGN_BASENAME" != "top.sv" ]; then \
		echo "Renaming $$DESIGN_BASENAME to top.sv"; \
		mv "$$DESIGN_FILE" "$(BUILD_DIR)/top.sv"; \
	else \
		echo "File is already named top.sv, no need to rename."; \
	fi

