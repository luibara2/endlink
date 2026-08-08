package org.cloudburstmc.protocol.bedrock.data.skin;

import lombok.Data;

import java.util.UUID;

@Data
public class PersonaPieceData {

    private static final UUID NO_PACK = new UUID(0L, 0L);

    String id;
    PersonaPieceType pieceType;
    UUID packUuid;
    boolean isDefault;
    String productId;
    /**
     * The strings exactly as a pre-1.26.40 peer sent them, kept only when they did not parse.
     *
     * <p>Local delta. 1.26.40 types these two fields — the piece type became an int enum and the
     * pack id a real UUID — but every earlier version sends them as free text, and upstream's
     * constructor parses that text with {@code PersonaPieceType.fromName} and {@code UUID.fromString},
     * both of which throw. A proxy cannot afford that: one unrecognised persona piece would fail the
     * whole skin, and a skin failure fails PlayerList / AddPlayer / PlayerSkin, which then get
     * raw-forwarded — harmless at matching versions, corrupt across them. Mojang also adds persona
     * piece types over time, so an unknown name is a matter of when, not if.</p>
     *
     * <p>Keeping the original text makes the legacy path lossless: what came in is what goes out,
     * even when this build has never heard of the piece. Cross-protocol still degrades — a 1.26.40
     * client gets {@code UNKNOWN} for a name we could not resolve — but a slightly wrong persona
     * piece beats a dropped packet.</p>
     */
    String rawType;
    String rawPackId;

    public PersonaPieceData(String id,
                            String type,
                            String packId,
                            boolean isDefault,
                            String productId) {
        this.id = id;
        this.pieceType = PersonaPieceType.fromNameOrUnknown(type);
        if (this.pieceType == PersonaPieceType.UNKNOWN && type != null) {
            this.rawType = type;
        }
        UUID parsed;
        try {
            parsed = packId == null || packId.isEmpty() ? NO_PACK : UUID.fromString(packId);
        } catch (IllegalArgumentException e) {
            parsed = NO_PACK;
        }
        this.packUuid = parsed;
        if (parsed == NO_PACK && packId != null) {
            this.rawPackId = packId;
        }
        this.isDefault = isDefault;
        this.productId = productId;
    }

    public PersonaPieceData(String id,
                            PersonaPieceType pieceType,
                            UUID packId,
                            boolean isDefault,
                            String productId) {
        this.id = id;
        this.pieceType = pieceType;
        this.packUuid = packId;
        this.isDefault = isDefault;
        this.productId = productId;
    }

    /** The original text when it did not parse, so a legacy peer gets back exactly what it sent. */
    public String getPackId() {
        return rawPackId != null ? rawPackId : packUuid.toString();
    }

    public String getType() {
        return rawType != null ? rawType : pieceType.getSerializeName();
    }
}
