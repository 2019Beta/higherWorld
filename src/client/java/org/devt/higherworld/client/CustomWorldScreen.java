package org.devt.higherworld.client;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.function.Consumer;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import org.devt.higherworld.world.CustomWorldSettings;

/** Complete five-page editor for the immutable custom-world generation preset. */
public final class CustomWorldScreen extends Screen {
    private enum Page {
        BASIC("basic"), ORES("ores"), LAKES("lakes"), CAVES("caves"), ADVANCED("advanced");

        private final String id;

        Page(String id) {
            this.id = id;
        }
    }

    private final Screen parent;
    private CustomWorldSettings draft;
    private Page page = Page.BASIC;
    private CustomSettingsList settingsList;
    private final Map<String, TextFieldWidget> fields = new LinkedHashMap<>();
    private final Set<String> invalidFields = new LinkedHashSet<>();
    private String error;

    public CustomWorldScreen(Screen parent) {
        super(Text.translatable("screen.higherworld.custom.title"));
        this.parent = parent;
        this.draft = CustomWorldSettings.clientSelection();
    }

    @Override
    protected void init() {
        fields.clear();
        invalidFields.clear();
        int contentTop = 80;
        int footerTop = height - 54;
        int listWidth = Math.min(760, Math.max(300, width - 20));
        int listHeight = Math.max(40, footerTop - contentTop);
        settingsList = new CustomSettingsList(
                client, listWidth, listHeight, contentTop, CustomSettingsList.ROW_HEIGHT);
        settingsList.setX((width - listWidth) / 2);
        addDrawableChild(settingsList);
        buildTabs();
        buildPage();
        buildFooter();
    }

    private void buildTabs() {
        int gap = 4;
        int left = Math.max(4, (width - (5 * 116 + 4 * gap)) / 2);
        for (int index = 0; index < Page.values().length; index++) {
            Page target = Page.values()[index];
            ButtonWidget button = ButtonWidget.builder(
                            Text.translatable("custom.page." + target.id),
                            ignored -> switchPage(target))
                    .dimensions(left + index * (116 + gap), 52, 116, 20)
                    .tooltip(Tooltip.of(Text.translatable("custom.page." + target.id + ".tooltip")))
                    .build();
            button.active = target != page;
            addDrawableChild(button);
        }
    }

    private void buildPage() {
        switch (page) {
            case BASIC -> buildBasicPage();
            case ORES -> buildOresPage();
            case LAKES -> buildLakesPage();
            case CAVES -> buildCavesPage();
            case ADVANCED -> buildAdvancedPage();
        }
    }

    private void buildBasicPage() {
        settingsList.addHeader(Text.translatable("custom.group.basic"));
        CyclingButtonWidget<Integer> depth = CyclingButtonWidget.<Integer>builder(
                        CustomWorldSettings::depthText, draft.generationDepth())
                .values(CustomWorldSettings.depthOptions())
                .tooltip(value -> Tooltip.of(Text.translatable(
                        "custom.field.generation_depth.tooltip")))
                .build(Text.translatable("custom.field.generation_depth"),
                        (button, value) -> draft = draft.withGenerationDepth(value));
        depth.setWidth(230);
        settingsList.addRow(Text.translatable("custom.field.generation_depth"), depth);

        addCheckbox("strongholds", draft.strongholds(), value ->
                draft = draft.toBuilder().strongholds(value).build());
        addCheckbox("alternate_strongholds_positions", draft.alternateStrongholdsPositions(), value ->
                draft = draft.toBuilder().alternateStrongholdsPositions(value).build());
        addCheckbox("villages", draft.villages(), value ->
                draft = draft.toBuilder().villages(value).build());
        addCheckbox("mineshafts", draft.mineshafts(), value ->
                draft = draft.toBuilder().mineshafts(value).build());
        addCheckbox("temples", draft.temples(), value ->
                draft = draft.toBuilder().temples(value).build());
        addCheckbox("ocean_monuments", draft.oceanMonuments(), value ->
                draft = draft.toBuilder().oceanMonuments(value).build());
        addCheckbox("woodland_mansions", draft.woodlandMansions(), value ->
                draft = draft.toBuilder().woodlandMansions(value).build());
        addCheckbox("ravines", draft.ravines(), value ->
                draft = draft.toBuilder().ravines(value).build());
        addCheckbox("dungeons", draft.dungeons(), value ->
                draft = draft.toBuilder().dungeons(value).build());
        addTextField("dungeonCount", "custom.field.dungeon_count", Integer.toString(draft.dungeonCount()));
        addTextField("biome", "custom.field.biome", draft.biome() == null ? "" : draft.biome());
        addTextField("biomeSize", "custom.field.biome_size", Integer.toString(draft.biomeSize()));
        addTextField("riverSize", "custom.field.river_size", Integer.toString(draft.riverSize()));
    }

    private void buildOresPage() {
        settingsList.addHeader(Text.translatable("custom.group.ores"));
        for (int index = 0; index < draft.standardOres().size(); index++) {
            CustomWorldSettings.OreSettings ore = draft.standardOres().get(index);
            addOreRow(false, index, ore);
        }
        for (int index = 0; index < draft.periodicGaussianOres().size(); index++) {
            CustomWorldSettings.OreSettings ore = draft.periodicGaussianOres().get(index);
            addOreRow(true, index, ore);
        }
        ButtonWidget addUniform = ButtonWidget.builder(
                        Text.translatable("custom.ore.add_uniform"),
                        ignored -> openOreEditor(false, -1, defaultUniformOre()))
                .width(220)
                .tooltip(Tooltip.of(Text.translatable("custom.ore.add_uniform.tooltip")))
                .build();
        settingsList.addRow(Text.empty(), addUniform);
        ButtonWidget addPeriodic = ButtonWidget.builder(
                        Text.translatable("custom.ore.add_periodic"),
                        ignored -> openOreEditor(true, -1, defaultPeriodicOre()))
                .width(220)
                .tooltip(Tooltip.of(Text.translatable("custom.ore.add_periodic.tooltip")))
                .build();
        settingsList.addRow(Text.empty(), addPeriodic);
    }

    private void addOreRow(boolean periodic, int index, CustomWorldSettings.OreSettings ore) {
        Text summary = Text.translatable("custom.ore.summary",
                periodic ? Text.translatable("custom.ore.type.periodic")
                        : Text.translatable("custom.ore.type.uniform"),
                ore.blockstate(), ore.spawnSize(), ore.spawnTries(), ore.spawnProbability());
        ButtonWidget edit = ButtonWidget.builder(Text.translatable("custom.edit"),
                        ignored -> openOreEditor(periodic, index, ore))
                .width(70)
                .tooltip(Tooltip.of(Text.translatable("custom.edit.tooltip")))
                .build();
        ButtonWidget delete = ButtonWidget.builder(Text.translatable("custom.delete"),
                        ignored -> removeOre(periodic, index))
                .width(70)
                .tooltip(Tooltip.of(Text.translatable("custom.delete.tooltip")))
                .build();
        settingsList.addRow(summary, edit, delete);
    }

    private void buildLakesPage() {
        settingsList.addHeader(Text.translatable("custom.group.lakes"));
        for (int index = 0; index < draft.lakes().size(); index++) {
            final int lakeIndex = index;
            CustomWorldSettings.LakeSettings lake = draft.lakes().get(index);
            Text summary = Text.translatable("custom.lake.summary", lake.block(),
                    lake.biomeSelect().name(), lake.biomes().size());
            ButtonWidget edit = ButtonWidget.builder(Text.translatable("custom.edit"),
                            ignored -> openLakeEditor(lakeIndex, lake))
                    .width(70)
                    .tooltip(Tooltip.of(Text.translatable("custom.edit.tooltip")))
                    .build();
            ButtonWidget delete = ButtonWidget.builder(Text.translatable("custom.delete"),
                            ignored -> removeLake(lakeIndex))
                    .width(70)
                    .tooltip(Tooltip.of(Text.translatable("custom.delete.tooltip")))
                    .build();
            settingsList.addRow(summary, edit, delete);
        }
        ButtonWidget add = ButtonWidget.builder(Text.translatable("custom.lake.add"),
                        ignored -> openLakeEditor(-1, defaultLake()))
                .width(220)
                .tooltip(Tooltip.of(Text.translatable("custom.lake.add.tooltip")))
                .build();
        settingsList.addRow(Text.empty(), add);
    }

    private void buildCavesPage() {
        settingsList.addHeader(Text.translatable("custom.group.caves"));
        for (int index = 0; index < draft.caves().size(); index++) {
            final int caveIndex = index;
            CustomWorldSettings.CaveSettings cave = draft.caves().get(index);
            Text summary = Text.translatable("custom.cave.summary", cave.caveBlock(),
                    cave.caveMinHeight(), cave.caveMaxHeight(), cave.caveRarity());
            ButtonWidget edit = ButtonWidget.builder(Text.translatable("custom.edit"),
                            ignored -> openCaveEditor(caveIndex, cave))
                    .width(70)
                    .tooltip(Tooltip.of(Text.translatable("custom.edit.tooltip")))
                    .build();
            ButtonWidget delete = ButtonWidget.builder(Text.translatable("custom.delete"),
                            ignored -> removeCave(caveIndex))
                    .width(70)
                    .tooltip(Tooltip.of(Text.translatable("custom.delete.tooltip")))
                    .build();
            settingsList.addRow(summary, edit, delete);
        }
        ButtonWidget add = ButtonWidget.builder(Text.translatable("custom.cave.add"),
                        ignored -> openCaveEditor(-1,
                                draft.caves().isEmpty() ? standardCave() : draft.caves().get(0)))
                .width(220)
                .tooltip(Tooltip.of(Text.translatable("custom.cave.add.tooltip")))
                .build();
        settingsList.addRow(Text.empty(), add);
    }

    private void buildAdvancedPage() {
        settingsList.addHeader(Text.translatable("custom.group.expected"));
        addNumber("expectedBaseHeight", "custom.field.expected_base_height", draft.expectedBaseHeight());
        addNumber("expectedHeightVariation", "custom.field.expected_height_variation", draft.expectedHeightVariation());
        addNumber("actualHeight", "custom.field.actual_height", draft.actualHeight());

        settingsList.addHeader(Text.translatable("custom.group.height"));
        addNumber("heightVariationFactor", "custom.field.height_variation_factor", draft.heightVariationFactor());
        addNumber("specialHeightVariationFactorBelowAverageY",
                "custom.field.special_height_variation_factor_below_average_y",
                draft.specialHeightVariationFactorBelowAverageY());
        addNumber("heightVariationOffset", "custom.field.height_variation_offset", draft.heightVariationOffset());
        addNumber("heightFactor", "custom.field.height_factor", draft.heightFactor());
        addNumber("heightOffset", "custom.field.height_offset", draft.heightOffset());

        settingsList.addHeader(Text.translatable("custom.group.depth"));
        addNumber("depthNoiseFrequencyX", "custom.field.depth_noise_frequency_x", draft.depthNoiseFrequencyX());
        addNumber("depthNoiseFrequencyZ", "custom.field.depth_noise_frequency_z", draft.depthNoiseFrequencyZ());
        addTextField("depthNoiseOctaves", "custom.field.depth_noise_octaves",
                Integer.toString(draft.depthNoiseOctaves()));
        addNumber("depthNoiseFactor", "custom.field.depth_noise_factor", draft.depthNoiseFactor());
        addNumber("depthNoiseOffset", "custom.field.depth_noise_offset", draft.depthNoiseOffset());

        settingsList.addHeader(Text.translatable("custom.group.selector"));
        addNumber("selectorNoiseFrequencyX", "custom.field.selector_noise_frequency_x", draft.selectorNoiseFrequencyX());
        addNumber("selectorNoiseFrequencyY", "custom.field.selector_noise_frequency_y", draft.selectorNoiseFrequencyY());
        addNumber("selectorNoiseFrequencyZ", "custom.field.selector_noise_frequency_z", draft.selectorNoiseFrequencyZ());
        addTextField("selectorNoiseOctaves", "custom.field.selector_noise_octaves",
                Integer.toString(draft.selectorNoiseOctaves()));
        addNumber("selectorNoiseFactor", "custom.field.selector_noise_factor", draft.selectorNoiseFactor());
        addNumber("selectorNoiseOffset", "custom.field.selector_noise_offset", draft.selectorNoiseOffset());

        settingsList.addHeader(Text.translatable("custom.group.low"));
        addNumber("lowNoiseFrequencyX", "custom.field.low_noise_frequency_x", draft.lowNoiseFrequencyX());
        addNumber("lowNoiseFrequencyY", "custom.field.low_noise_frequency_y", draft.lowNoiseFrequencyY());
        addNumber("lowNoiseFrequencyZ", "custom.field.low_noise_frequency_z", draft.lowNoiseFrequencyZ());
        addTextField("lowNoiseOctaves", "custom.field.low_noise_octaves",
                Integer.toString(draft.lowNoiseOctaves()));
        addNumber("lowNoiseFactor", "custom.field.low_noise_factor", draft.lowNoiseFactor());
        addNumber("lowNoiseOffset", "custom.field.low_noise_offset", draft.lowNoiseOffset());

        settingsList.addHeader(Text.translatable("custom.group.high"));
        addNumber("highNoiseFrequencyX", "custom.field.high_noise_frequency_x", draft.highNoiseFrequencyX());
        addNumber("highNoiseFrequencyY", "custom.field.high_noise_frequency_y", draft.highNoiseFrequencyY());
        addNumber("highNoiseFrequencyZ", "custom.field.high_noise_frequency_z", draft.highNoiseFrequencyZ());
        addTextField("highNoiseOctaves", "custom.field.high_noise_octaves",
                Integer.toString(draft.highNoiseOctaves()));
        addNumber("highNoiseFactor", "custom.field.high_noise_factor", draft.highNoiseFactor());
        addNumber("highNoiseOffset", "custom.field.high_noise_offset", draft.highNoiseOffset());

        settingsList.addHeader(Text.translatable("custom.group.sampling"));
        addSampleSize("noiseSampleSizeX", draft.noiseSampleSizeX());
        addSampleSize("noiseSampleSizeY", draft.noiseSampleSizeY());
        addSampleSize("noiseSampleSizeZ", draft.noiseSampleSizeZ());
        ButtonWidget sync = ButtonWidget.builder(Text.translatable("custom.sync_xz"), ignored -> syncXZ())
                .width(180)
                .tooltip(Tooltip.of(Text.translatable("custom.sync_xz.tooltip")))
                .build();
        settingsList.addRow(Text.empty(), sync);
    }

    private void buildFooter() {
        int widthEach = 96;
        int gap = 4;
        int total = widthEach * 4 + gap * 3;
        int left = (width - total) / 2;
        ButtonWidget preset = ButtonWidget.builder(Text.translatable("custom.preset"), ignored -> openPreset())
                .dimensions(left, height - 28, widthEach, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.preset.tooltip")))
                .build();
        ButtonWidget reset = ButtonWidget.builder(Text.translatable("custom.reset"), ignored -> resetDraft())
                .dimensions(left + widthEach + gap, height - 28, widthEach, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.reset.tooltip")))
                .build();
        ButtonWidget done = ButtonWidget.builder(Text.translatable("gui.done"), ignored -> saveAndClose())
                .dimensions(left + (widthEach + gap) * 2, height - 28, widthEach, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.done.tooltip")))
                .build();
        ButtonWidget cancel = ButtonWidget.builder(Text.translatable("gui.cancel"), ignored -> close())
                .dimensions(left + (widthEach + gap) * 3, height - 28, widthEach, 20)
                .tooltip(Tooltip.of(Text.translatable("custom.cancel.tooltip")))
                .build();
        addDrawableChild(preset);
        addDrawableChild(reset);
        addDrawableChild(done);
        addDrawableChild(cancel);
    }

    private void addCheckbox(String id, boolean checked, Consumer<Boolean> callback) {
        CheckboxWidget checkbox = CheckboxWidget.builder(
                        Text.translatable("custom.field." + id), textRenderer)
                .pos(0, 0)
                .checked(checked)
                .maxWidth(420)
                .tooltip(Tooltip.of(Text.translatable("custom.field." + id + ".tooltip")))
                .callback((widget, value) -> callback.accept(value))
                .build();
        checkbox.setMaxWidth(420, textRenderer);
        settingsList.addCheckboxRow(checkbox);
    }

    private void addTextField(String id, String labelKey, String value) {
        TextFieldWidget field = new TextFieldWidget(textRenderer, 0, 0, 230, 20, Text.empty());
        field.setMaxLength(4096);
        field.setText(value);
        field.setTooltip(Tooltip.of(Text.translatable(labelKey + ".tooltip")));
        fields.put(id, field);
        settingsList.addRow(Text.translatable(labelKey), field);
    }

    private void addNumber(String id, String labelKey, double value) {
        addTextField(id, labelKey, CustomSettingsParsers.formatNumber(value));
    }

    private void addSampleSize(String id, int value) {
        CyclingButtonWidget<Integer> button = CyclingButtonWidget.<Integer>builder(
                        value1 -> Text.literal(Integer.toString(value1)), value)
                .values(List.of(1, 2, 4, 8, 16))
                .tooltip(option -> Tooltip.of(Text.translatable("custom.field." + id + ".tooltip")))
                .build(Text.translatable("custom.field." + id), (widget, selected) -> {
                    CustomWorldSettings.Builder builder = draft.toBuilder();
                    switch (id) {
                        case "noiseSampleSizeX" -> builder.noiseSampleSizeX(selected);
                        case "noiseSampleSizeY" -> builder.noiseSampleSizeY(selected);
                        case "noiseSampleSizeZ" -> builder.noiseSampleSizeZ(selected);
                        default -> throw new IllegalStateException("unknown sample size " + id);
                    }
                    draft = builder.build();
                });
        button.setWidth(230);
        settingsList.addRow(Text.translatable("custom.field." + id), button);
    }

    private void syncXZ() {
        String[] prefixes = {"depthNoise", "selectorNoise", "lowNoise", "highNoise"};
        for (String prefix : prefixes) {
            TextFieldWidget x = fields.get(prefix + "FrequencyX");
            TextFieldWidget z = fields.get(prefix + "FrequencyZ");
            if (x != null && z != null) {
                x.setText(z.getText());
            }
        }
    }

    private void switchPage(Page target) {
        if (target == page || !commitCurrentPage()) {
            return;
        }
        page = target;
        error = null;
        clearAndInit();
    }

    private boolean commitCurrentPage() {
        if (page != Page.BASIC && page != Page.ADVANCED) {
            return true;
        }
        try {
            CustomWorldSettings.Builder builder = draft.toBuilder();
            if (page == Page.BASIC) {
                builder.dungeonCount(CustomSettingsParsers.integer(
                        field("dungeonCount").getText(), "dungeonCount"));
                String biome = field("biome").getText().trim();
                builder.biome(biome.isEmpty() ? null : biome);
                builder.biomeSize(CustomSettingsParsers.integer(
                        field("biomeSize").getText(), "biomeSize"));
                builder.riverSize(CustomSettingsParsers.integer(
                        field("riverSize").getText(), "riverSize"));
            } else {
                for (String id : fields.keySet()) {
                    applyAdvancedNumber(builder, id, field(id).getText());
                }
            }
            draft = builder.build();
            clearInvalidFields();
            error = null;
            return true;
        } catch (RuntimeException exception) {
            invalidFields.clear();
            invalidFields.addAll(fields.keySet());
            for (TextFieldWidget field : fields.values()) {
                field.setEditableColor(0xFFFF5555);
            }
            error = exception.getMessage() == null
                    ? Text.translatable("custom.error.invalid").getString()
                    : exception.getMessage();
            return false;
        }
    }

    private void applyAdvancedNumber(CustomWorldSettings.Builder builder, String id, String text) {
        if (id.equals("depthNoiseOctaves") || id.equals("selectorNoiseOctaves")
                || id.equals("lowNoiseOctaves") || id.equals("highNoiseOctaves")) {
            int value = CustomSettingsParsers.integer(text, id);
            switch (id) {
                case "depthNoiseOctaves" -> builder.depthNoiseOctaves(value);
                case "selectorNoiseOctaves" -> builder.selectorNoiseOctaves(value);
                case "lowNoiseOctaves" -> builder.lowNoiseOctaves(value);
                case "highNoiseOctaves" -> builder.highNoiseOctaves(value);
                default -> throw new IllegalStateException(id);
            }
            return;
        }
        double value = CustomSettingsParsers.finite(text, id);
        switch (id) {
            case "expectedBaseHeight" -> builder.expectedBaseHeight(value);
            case "expectedHeightVariation" -> builder.expectedHeightVariation(value);
            case "actualHeight" -> builder.actualHeight(value);
            case "heightVariationFactor" -> builder.heightVariationFactor(value);
            case "specialHeightVariationFactorBelowAverageY" ->
                    builder.specialHeightVariationFactorBelowAverageY(value);
            case "heightVariationOffset" -> builder.heightVariationOffset(value);
            case "heightFactor" -> builder.heightFactor(value);
            case "heightOffset" -> builder.heightOffset(value);
            case "depthNoiseFrequencyX" -> builder.depthNoiseFrequencyX(value);
            case "depthNoiseFrequencyZ" -> builder.depthNoiseFrequencyZ(value);
            case "depthNoiseFactor" -> builder.depthNoiseFactor(value);
            case "depthNoiseOffset" -> builder.depthNoiseOffset(value);
            case "selectorNoiseFrequencyX" -> builder.selectorNoiseFrequencyX(value);
            case "selectorNoiseFrequencyY" -> builder.selectorNoiseFrequencyY(value);
            case "selectorNoiseFrequencyZ" -> builder.selectorNoiseFrequencyZ(value);
            case "selectorNoiseFactor" -> builder.selectorNoiseFactor(value);
            case "selectorNoiseOffset" -> builder.selectorNoiseOffset(value);
            case "lowNoiseFrequencyX" -> builder.lowNoiseFrequencyX(value);
            case "lowNoiseFrequencyY" -> builder.lowNoiseFrequencyY(value);
            case "lowNoiseFrequencyZ" -> builder.lowNoiseFrequencyZ(value);
            case "lowNoiseFactor" -> builder.lowNoiseFactor(value);
            case "lowNoiseOffset" -> builder.lowNoiseOffset(value);
            case "highNoiseFrequencyX" -> builder.highNoiseFrequencyX(value);
            case "highNoiseFrequencyY" -> builder.highNoiseFrequencyY(value);
            case "highNoiseFrequencyZ" -> builder.highNoiseFrequencyZ(value);
            case "highNoiseFactor" -> builder.highNoiseFactor(value);
            case "highNoiseOffset" -> builder.highNoiseOffset(value);
            default -> throw new IllegalArgumentException("unknown advanced field " + id);
        }
    }

    private TextFieldWidget field(String id) {
        TextFieldWidget field = fields.get(id);
        if (field == null) {
            throw new IllegalStateException("missing field " + id);
        }
        return field;
    }

    private void clearInvalidFields() {
        invalidFields.clear();
        for (TextFieldWidget field : fields.values()) {
            field.setEditableColor(0xE0E0E0);
        }
    }

    private void removeOre(boolean periodic, int index) {
        List<CustomWorldSettings.OreSettings> values = new ArrayList<>(
                periodic ? draft.periodicGaussianOres() : draft.standardOres());
        if (index < 0 || index >= values.size()) {
            return;
        }
        values.remove(index);
        CustomWorldSettings.Builder builder = draft.toBuilder();
        if (periodic) {
            builder.periodicGaussianOres(values);
        } else {
            builder.standardOres(values);
        }
        draft = builder.build();
        clearAndInit();
    }

    private void removeLake(int index) {
        List<CustomWorldSettings.LakeSettings> values = new ArrayList<>(draft.lakes());
        if (index < 0 || index >= values.size()) return;
        values.remove(index);
        draft = draft.toBuilder().lakes(values).build();
        clearAndInit();
    }

    private void removeCave(int index) {
        List<CustomWorldSettings.CaveSettings> values = new ArrayList<>(draft.caves());
        if (index < 0 || index >= values.size()) return;
        values.remove(index);
        draft = draft.toBuilder().caves(values).build();
        clearAndInit();
    }

    private void openOreEditor(boolean periodic, int index, CustomWorldSettings.OreSettings value) {
        if (!commitCurrentPage()) return;
        client.setScreen(new CustomOreEntryScreen(this, periodic, index, value, this::applyOreResult));
    }

    private void openLakeEditor(int index, CustomWorldSettings.LakeSettings value) {
        if (!commitCurrentPage()) return;
        client.setScreen(new CustomLakeEntryScreen(this, index, value, this::applyLakeResult));
    }

    private void openCaveEditor(int index, CustomWorldSettings.CaveSettings value) {
        if (!commitCurrentPage()) return;
        client.setScreen(new CustomCaveEntryScreen(this, index, value, this::applyCaveResult));
    }

    private void applyOreResult(CustomOreEntryScreen.Result result) {
        List<CustomWorldSettings.OreSettings> ordinary = new ArrayList<>(draft.standardOres());
        List<CustomWorldSettings.OreSettings> periodic = new ArrayList<>(draft.periodicGaussianOres());
        List<CustomWorldSettings.OreSettings> target = result.periodic() ? periodic : ordinary;
        if (result.originalPeriodic() == result.periodic()) {
            if (result.index() < 0) target.add(result.settings());
            else target.set(result.index(), result.settings());
        } else {
            List<CustomWorldSettings.OreSettings> original = result.originalPeriodic() ? periodic : ordinary;
            if (result.index() >= 0 && result.index() < original.size()) original.remove(result.index());
            target.add(result.settings());
        }
        // Building the complete draft is the validation boundary for an entry.
        draft = draft.toBuilder().standardOres(ordinary).periodicGaussianOres(periodic).build();
        clearAndInit();
    }

    private void applyLakeResult(int index, CustomWorldSettings.LakeSettings result) {
        List<CustomWorldSettings.LakeSettings> values = new ArrayList<>(draft.lakes());
        if (index < 0) values.add(result);
        else values.set(index, result);
        draft = draft.toBuilder().lakes(values).build();
        clearAndInit();
    }

    private void applyCaveResult(int index, CustomWorldSettings.CaveSettings result) {
        List<CustomWorldSettings.CaveSettings> values = new ArrayList<>(draft.caves());
        if (index < 0) values.add(result);
        else values.set(index, result);
        draft = draft.toBuilder().caves(values).build();
        clearAndInit();
    }

    private void openPreset() {
        if (commitCurrentPage()) {
            client.setScreen(new CustomPresetScreen(this, draft));
        }
    }

    void applyPreset(CustomWorldSettings value) {
        draft = value;
        error = null;
        clearAndInit();
    }

    private void resetDraft() {
        draft = CustomWorldSettings.customDefaults();
        error = null;
        clearAndInit();
    }

    private void saveAndClose() {
        if (!commitCurrentPage()) return;
        try {
            draft.validate();
            CustomWorldSettings.setClientSelection(draft);
            close();
        } catch (RuntimeException exception) {
            error = exception.getMessage();
        }
    }

    @Override
    public void close() {
        if (client != null) {
            client.setScreen(parent);
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("screen.higherworld.custom.title"), width / 2, 20, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer,
                Text.translatable("custom.page." + page.id + ".description"), width / 2, 38, 0xA0A0A0);
        if (error != null && !error.isBlank()) {
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.literal(error), width / 2, height - 47, 0xFFFF5555);
        }
    }

    static CustomWorldSettings.CaveSettings standardCave() {
        return new CustomWorldSettings.CaveSettings("minecraft:air",
                Integer.MIN_VALUE / 16, Integer.MAX_VALUE / 16, 14, 14, 4, 4, 10,
                1.5, 6, .7, .92, .1, .75, .9, 4, 2, 4, -.7,
                List.of("minecraft:grass_block", "minecraft:dirt", "minecraft:stone",
                        "minecraft:deepslate"));
    }

    static CustomWorldSettings.OreSettings defaultUniformOre() {
        return new CustomWorldSettings.OreSettings("minecraft:iron_ore", null, 9, 20, 1.0,
                Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 0.0, 0.0, 1.0);
    }

    static CustomWorldSettings.OreSettings defaultPeriodicOre() {
        return new CustomWorldSettings.OreSettings("minecraft:lapis_ore", null, 7, 1,
                .933307775, Double.NEGATIVE_INFINITY, -.5, -.75, .11231704455, 3.0);
    }

    static CustomWorldSettings.LakeSettings defaultLake() {
        return new CustomWorldSettings.LakeSettings("minecraft:water",
                CustomWorldSettings.FilterType.EXCLUDE, Set.of(),
                CustomSettingsParsers.curve("-1:0.25,0:0.25,128:0.125,129:0.125"),
                CustomSettingsParsers.curve("0:0.015625"));
    }
}
