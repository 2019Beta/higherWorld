package org.devt.higherworld.world;

import net.minecraft.text.Text;

/** Structure families supported by HigherWorld below the vanilla world floor. */
public enum UndergroundStructure {
    MINESHAFT("mineshaft"),
    STRONGHOLD("stronghold"),
    ANCIENT_CITY("ancient_city"),
    TRIAL_CHAMBERS("trial_chambers");

    private final String id;

    UndergroundStructure(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public Text displayName() {
        return Text.translatable("structure.higherworld." + id);
    }

    public static UndergroundStructure byId(String id) {
        for (UndergroundStructure structure : values()) {
            if (structure.id.equals(id)) {
                return structure;
            }
        }
        return null;
    }
}
