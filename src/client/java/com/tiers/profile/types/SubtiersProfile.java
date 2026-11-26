package com.tiers.profile.types;

import com.tiers.misc.Mode;
import com.tiers.profile.GameMode;
import net.minecraft.resources.Identifier;

public class SubtiersProfile extends SuperProfile {
    public static final Identifier SUBTIERS_IMAGE = Identifier.fromNamespaceAndPath("minecraft", "textures/subtiers_logo.png");

    public SubtiersProfile(String apiUrl, String uuid, String extra) {
        super();
        addGamemodes();
        buildRequest(apiUrl, uuid, extra);
    }

    public SubtiersProfile(String json) {
        super();
        addGamemodes();
        parseJson(json);
    }

    private void addGamemodes() {
        gameModes.add(new GameMode(Mode.SUBTIERS_MINECART, "minecart"));
        gameModes.add(new GameMode(Mode.SUBTIERS_DIAMOND_VANILLA, "dia_crystal"));
        gameModes.add(new GameMode(Mode.SUBTIERS_DEBUFF, "debuff"));
        gameModes.add(new GameMode(Mode.SUBTIERS_ELYTRA, "elytra"));
        gameModes.add(new GameMode(Mode.SUBTIERS_SPEED, "speed"));
        gameModes.add(new GameMode(Mode.SUBTIERS_CREEPER, "creeper"));
        gameModes.add(new GameMode(Mode.SUBTIERS_MANHUNT, "manhunt"));
        gameModes.add(new GameMode(Mode.SUBTIERS_DIAMOND_SMP, "dia_smp"));
        gameModes.add(new GameMode(Mode.SUBTIERS_BOW, "bow"));
        gameModes.add(new GameMode(Mode.SUBTIERS_BED, "bed"));
        gameModes.add(new GameMode(Mode.SUBTIERS_OG_VANILLA, "og_vanilla"));
        gameModes.add(new GameMode(Mode.SUBTIERS_TRIDENT, "trident"));
        gameModes.add(new GameMode(Mode.SUBTIERS_VANILLA, "vanilla"));
        gameModes.add(new GameMode(Mode.SUBTIERS_UHC, "uhc"));
        gameModes.add(new GameMode(Mode.SUBTIERS_POT, "pot"));
        gameModes.add(new GameMode(Mode.SUBTIERS_NETHOP, "nethop"));
        gameModes.add(new GameMode(Mode.SUBTIERS_SMP, "smp"));
        gameModes.add(new GameMode(Mode.SUBTIERS_SWORD, "sword"));
        gameModes.add(new GameMode(Mode.SUBTIERS_AXE, "axe"));
        gameModes.add(new GameMode(Mode.SUBTIERS_MACE, "mace"));
        gameModes.add(new GameMode(Mode.SUBTIERS_SPEAR, "spear"));
        gameModes.add(new GameMode(Mode.SUBTIERS_NETH_POT, "neth_pot"));
        gameModes.add(new GameMode(Mode.SUBTIERS_LATE_GAME, "late_game"));
        gameModes.add(new GameMode(Mode.SUBTIERS_END_GAME, "end_game"));
    }
}
