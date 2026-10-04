package com.fruitfly.client.hud;

import com.fruitfly.brain.MotorDecoder;
import net.minecraft.util.ARGB;

import java.util.Locale;

/** Colours, labels and number formatting shared by the neuroscope widgets. All colours are ARGB ints. */
final class HudStyle {
    static final int BG = 0xB40B0F14;
    static final int BG_INSET = 0xC0000000;
    static final int BORDER = 0x50FFFFFF;
    static final int SEPARATOR = 0x30FFFFFF;
    static final int TEXT = 0xFFE8E8E8;
    static final int DIM = 0xFF8A939C;
    static final int BAR_BG = 0xFF20262D;
    static final int ACCENT = 0xFF9CDCFE;
    static final int RED = 0xFFFF4B4B;
    static final int ORANGE = 0xFFFFA040;
    static final int YELLOW = 0xFFFFE066;
    static final int GREEN = 0xFF55D66E;
    static final int BLUE = 0xFF5B9BFF;
    static final int CYAN = 0xFF40C4FF;
    static final int TEAL = 0xFF3FD2C7;
    static final int PURPLE = 0xFFB98CFF;
    static final int PINK = 0xFFFF7FB8;
    static final int GREY = 0xFFBDBDBD;
    static final int RASTER_LOW = 0xFF2F5C8F;

    private HudStyle() { }

    static int modeColor(MotorDecoder.Mode m) {
        if (m == null) return GREY;
        return switch (m) {
            case IDLE -> GREY;
            case FORWARD -> GREEN;
            case BACKWARD -> ORANGE;
            case HALT -> YELLOW;
            case BRAKE -> 0xFFFFB300;
            case ESCAPE -> RED;
            case FLYING -> CYAN;
            case LANDING -> TEAL;
            case GROOM -> PURPLE;
            case FEED -> PINK;
            case SONG -> 0xFF8C9EFF;
        };
    }

    static MotorDecoder.Mode mode(byte ordinal) {
        MotorDecoder.Mode[] v = MotorDecoder.Mode.values();
        int i = ordinal & 0xFF;
        return i < v.length ? v[i] : MotorDecoder.Mode.IDLE;
    }

    /**
     * Colour class of a population spec: descending neurons blue, motor neurons orange, sensory / early visual green,
     * Kenyon cells and projection neurons purple, other central-brain types pink, unknown grey.
     */
    static int populationColor(String spec) {
        if (spec == null) return GREY;
        String s = spec.trim();
        int slash = s.lastIndexOf('/');
        if (slash > 0 && slash == s.length() - 2) s = s.substring(0, slash);
        String low = s.toLowerCase(Locale.ROOT);
        String term = low.contains(":") ? low.substring(low.indexOf(':') + 1) : low;

        if (low.contains("kenyon") || term.startsWith("kc") || low.contains("alpn") || term.equals("pn")
                || low.contains("mbon") || term.startsWith("dan") || low.contains("class:dan") || term.startsWith("apl")) return PURPLE;
        if (low.contains("vnc_motor") || low.contains("cb_motor") || term.startsWith("mn") || low.contains("subclass:wm")
                || low.contains("subclass:fl") || low.contains("subclass:ml") || low.contains("subclass:hl")
                || low.contains("efferent") || term.startsWith("ttmn") || term.startsWith("dlm") || term.startsWith("dvm")) return ORANGE;
        if (low.contains("descending") || term.startsWith("dn") || term.startsWith("mdn") || term.startsWith("adn")
                || term.startsWith("pip10") || term.startsWith("pmp2") || term.startsWith("ovidn") || term.startsWith("gf")) return BLUE;
        if (low.contains("sensory") || low.contains("olfactory") || low.contains("gustatory") || low.contains("visual")
                || low.contains("hygro") || low.contains("thermo") || term.startsWith("orn") || term.startsWith("grn")
                || term.startsWith("jo") || term.startsWith("lb") || term.startsWith("phg") || term.startsWith("lc")
                || term.startsWith("lplc") || term.startsWith("lpc") || term.startsWith("l1") || term.startsWith("l2")
                || term.startsWith("l3") || term.startsWith("t4") || term.startsWith("t5") || term.startsWith("tm")
                || term.startsWith("mi") || term.startsWith("dm") || term.startsWith("ocg") || term.startsWith("r1")
                || term.startsWith("r7") || term.startsWith("r8") || term.startsWith("bm") || term.startsWith("pn_")) return GREEN;
        if (term.startsWith("pc1") || term.startsWith("pc2") || term.startsWith("p1") || term.startsWith("gng")
                || term.startsWith("g2n") || term.startsWith("cx") || low.contains("dimorphism") || term.startsWith("fru")
                || term.startsWith("dsx") || term.startsWith("an")) return PINK;
        return GREY;
    }

    /** Short human label for a population spec: strips {@code prefix:/class:/...}, abbreviates long class names. */
    static String populationLabel(String spec) {
        if (spec == null) return "?";
        String s = spec.trim();
        String side = "";
        int slash = s.lastIndexOf('/');
        if (slash > 0 && slash == s.length() - 2) {
            side = s.substring(slash);
            s = s.substring(0, slash);
        }
        String prefix = "";
        int colon = s.indexOf(':');
        if (colon > 0) {
            prefix = s.substring(0, colon);
            s = s.substring(colon + 1);
        }
        s = switch (s) {
            case "Kenyon_Cell" -> "KC";
            case "ALPN" -> "PN";
            case "descending_neuron" -> "all DN";
            case "vnc_motor" -> "all MN";
            case "olfactory" -> "ORN";
            case "gustatory" -> "GRN";
            case "mechanosensory" -> "mech";
            case "GNG232" -> "G2N-1";
            case "DNg62" -> "aDN1";
            case "DNge078" -> "aDN2";
            case "DNg67" -> "Fudog";
            case "DNge080" -> "Rounddn";
            case "DNp01" -> "DNp01 GF";
            default -> s;
        };
        if ((prefix.equals("prefix") || prefix.equals("contains")) && s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (prefix.equals("prefix") || prefix.equals("contains")) s = s + "*";
        return s + side;
    }

    /** 950 → "950", 3200 → "3.2k", 81000 → "81k", 1.2M → "1.2M". */
    static String fmtCount(long n) {
        if (n < 1000) return Long.toString(n);
        if (n < 10_000) return String.format(Locale.ROOT, "%.1fk", n / 1000.0);
        if (n < 1_000_000) return (n / 1000) + "k";
        return String.format(Locale.ROOT, "%.1fM", n / 1e6);
    }

    static String fmtHz(float hz) {
        if (hz < 0.05f) return "0";
        if (hz < 10f) return String.format(Locale.ROOT, "%.1f", hz);
        return Integer.toString(Math.round(hz));
    }

    static int withAlpha(int argb, int alpha) { return (argb & 0x00FFFFFF) | ((alpha & 0xFF) << 24); }

    static int lerp(float t, int a, int b) { return ARGB.srgbLerp(Math.max(0f, Math.min(1f, t)), a, b); }

    static int grey(int lum) {
        int l = Math.max(0, Math.min(255, lum));
        return ARGB.color(255, l, l, l);
    }
}
