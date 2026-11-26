package com.tiers.misc;

import com.tiers.textures.Icons;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.util.CommonColors;

import java.util.Arrays;
import java.util.Locale;

public enum Mode {
    MCTIERS_VANILLA(Category.MCTIERS, "\uF000", "Vanilla"),
    MCTIERS_UHC(Category.MCTIERS, "\uF001", "UHC"),
    MCTIERS_POT(Category.MCTIERS, "\uF002", "Pot"),
    MCTIERS_NETH_OP(Category.MCTIERS, "\uF003", "Neth Op"),
    MCTIERS_SMP(Category.MCTIERS, "\uF004", "Smp"),
    MCTIERS_SWORD(Category.MCTIERS, "\uF005", "Sword"),
    MCTIERS_AXE(Category.MCTIERS, "\uF006", "Axe"),
    MCTIERS_MACE(Category.MCTIERS, "\uF007", "Mace"),
    MCTIERS_SPEAR(Category.MCTIERS, "\uF008", "Spear Mace"),
    MCTIERS_LATE_GAME(Category.MCTIERS, "\uF008", "Late Game"),
    MCTIERS_END_GAME(Category.MCTIERS, "\uF009", "End Game"),

    PVPTIERS_CRYSTAL(Category.PVPTIERS, "\uF000", "Crystal"),
    PVPTIERS_SWORD(Category.PVPTIERS, "\uF005", "Sword"),
    PVPTIERS_UHC(Category.PVPTIERS, "\uF001", "UHC"),
    PVPTIERS_POT(Category.PVPTIERS, "\uF002", "Pot"),
    PVPTIERS_NETH_POT(Category.PVPTIERS, "\uF003", "Neth Pot"),
    PVPTIERS_NETHOP(Category.PVPTIERS, "\uF003", "Nethop"),
    PVPTIERS_SMP(Category.PVPTIERS, "\uF004", "Smp"),
    PVPTIERS_AXE(Category.PVPTIERS, "\uF006", "Axe"),
    PVPTIERS_MACE(Category.PVPTIERS, "\uF007", "Mace"),

    SUBTIERS_MINECART(Category.SUBTIERS, "\uF000", "Minecart"),
    SUBTIERS_DIAMOND_VANILLA(Category.SUBTIERS, "\uF001", "Diamond Vanilla"),
    SUBTIERS_DEBUFF(Category.SUBTIERS, "\uF002", "DeBuff"),
    SUBTIERS_ELYTRA(Category.SUBTIERS, "\uF003", "Elytra"),
    SUBTIERS_SPEED(Category.SUBTIERS, "\uF004", "Speed"),
    SUBTIERS_CREEPER(Category.SUBTIERS, "\uF005", "Creeper"),
    SUBTIERS_MANHUNT(Category.SUBTIERS, "\uF006", "Manhunt"),
    SUBTIERS_DIAMOND_SMP(Category.SUBTIERS, "\uF007", "Diamond Smp"),
    SUBTIERS_BOW(Category.SUBTIERS, "\uF008", "Bow"),
    SUBTIERS_BED(Category.SUBTIERS, "\uF009", "Bed"),
    SUBTIERS_OG_VANILLA(Category.SUBTIERS, "\uF00A", "OG Vanilla"),
    SUBTIERS_TRIDENT(Category.SUBTIERS, "\uF00B", "Trident"),
    SUBTIERS_VANILLA(Category.SUBTIERS, "\uF00C", "Vanilla"),
    SUBTIERS_UHC(Category.SUBTIERS, "\uF00D", "UHC"),
    SUBTIERS_POT(Category.SUBTIERS, "\uF00E", "Pot"),
    SUBTIERS_NETHOP(Category.SUBTIERS, "\uF00F", "Nethop"),
    SUBTIERS_SMP(Category.SUBTIERS, "\uF010", "SMP"),
    SUBTIERS_SWORD(Category.SUBTIERS, "\uF011", "Sword"),
    SUBTIERS_AXE(Category.SUBTIERS, "\uF012", "Axe"),
    SUBTIERS_MACE(Category.SUBTIERS, "\uF013", "Mace"),
    SUBTIERS_SPEAR(Category.SUBTIERS, "\uF014", "Spear Mace"),
    SUBTIERS_NETH_POT(Category.SUBTIERS, "\uF003", "Neth Pot"),
    SUBTIERS_LATE_GAME(Category.SUBTIERS, "\uF008", "Late Game"),
    SUBTIERS_END_GAME(Category.SUBTIERS, "\uF009", "End Game");

    private final Category category;
    private final String unicode;
    private final String label;

    Mode(Category category, String unicode, String label) {
        this.category = category;
        this.unicode = unicode;
        this.label = label;
    }

    public enum Category {
        MCTIERS,
        PVPTIERS,
        SUBTIERS
    }

    public Component getIcon() {
        Identifier identifier = switch (category) {
            case MCTIERS -> Icons.identifierMCTiers;
            case PVPTIERS -> Icons.identifierPvPTiers;
            case SUBTIERS -> Icons.identifierSubtiers;
        };
        return Component.literal(unicode).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(identifier)).withColor(CommonColors.WHITE));
    }

    public Component getIconTag() {
        Identifier identifier = switch (category) {
            case MCTIERS -> Icons.identifierMCTiersTags;
            case PVPTIERS -> Icons.identifierPvPTiersTags;
            case SUBTIERS -> Icons.identifierSubtiersTags;
        };
        return Component.literal(unicode).setStyle(Style.EMPTY.withFont(new FontDescription.Resource(identifier)).withColor(CommonColors.WHITE));
    }

    public Component getTextLabel() {
        return Icons.colorText(label, name().toLowerCase(Locale.ROOT));
    }

    public static Mode[] getMCTiersValues() {
        return Arrays.stream(values()).filter(mode -> mode.toString().contains("MCTIERS")).toArray(Mode[]::new);
    }

    public static Mode[] getPvPTiersValues() {
        return Arrays.stream(values()).filter(mode -> mode.toString().contains("PVPTIERS")).toArray(Mode[]::new);
    }

    public static Mode[] getSubtiersValues() {
        return Arrays.stream(values()).filter(mode -> mode.toString().contains("SUBTIERS")).toArray(Mode[]::new);
    }
}
