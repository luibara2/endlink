package org.cloudburstmc.protocol.bedrock.data.skin;

import lombok.Data;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

@Data
public class PersonaPieceTintData {

    PersonaPieceType pieceType;
    /**
     * @deprecated since v2168, use colorsNew
     */
    List<String> colors;
    /**
     * @since v2168
     */
    List<Color> colorsNew;

    /** See {@link PersonaPieceData#rawType}: a name this build does not know must not fail the skin. */
    String rawType;

    public PersonaPieceTintData(String type, List<String> colors) {
        this.pieceType = PersonaPieceType.fromNameOrUnknown(type);
        if (this.pieceType == PersonaPieceType.UNKNOWN && type != null) {
            this.rawType = type;
        }
        this.colors = colors;
    }

    public PersonaPieceTintData(PersonaPieceType type, List<Color> colorsNew) {
        this.pieceType = type;
        this.colorsNew = colorsNew;
    }

    public String getType() {
        return rawType != null ? rawType : pieceType.getSerializeName();
    }

    /**
     * @deprecated since v2168, use getColorsNew
     */
    public List<String> getColors() {
        if ((colors == null || colors.isEmpty()) && colorsNew != null && !colorsNew.isEmpty()) {
            colors = new ArrayList<>(colorsNew.size());
            for (Color c : colorsNew) {
                if (c.getAlpha() == 0) {
                    colors.add("#0");
                } else {
                    colors.add(String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()));
                }
            }
        }
        return colors;
    }

    /**
     * @since v2168
     */
    public List<Color> getColorsNew() {
        if ((colorsNew == null || colorsNew.isEmpty()) && colors != null && !colors.isEmpty()) {
            colorsNew = new ArrayList<>(colors.size());
            for (String s : colors) {
                if (s.equals("#0")) {
                    colorsNew.add(new Color(0, true));
                } else {
                    colorsNew.add(new Color((int) Long.parseLong(s.startsWith("#") ? s.substring(1) : s, 16), true));
                }
            }
        }
        return colorsNew;
    }
}
