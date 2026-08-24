package org.cloudburstmc.protocol.bedrock.codec.v2168.serializer;

import io.netty.buffer.ByteBuf;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.v2168.BedrockCodecHelper_v2168;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.SetScoreSerializer_v291;
import org.cloudburstmc.protocol.bedrock.data.ScoreInfo;
import org.cloudburstmc.protocol.bedrock.packet.SetScorePacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SetScoreSerializer_v2168 extends SetScoreSerializer_v291 {

    public static final SetScoreSerializer_v2168 INSTANCE = new SetScoreSerializer_v2168();

    private static final String[] TYPES = {"remove", "changeplayer", "changeentity", "changefakeplayer"};

    private static final Logger log = LoggerFactory.getLogger(SetScoreSerializer_v2168.class);

    @Override
    public void serialize(ByteBuf buffer, BedrockCodecHelper helper, SetScorePacket packet) {
        helper.writeArray(buffer, packet.getInfos(), (buf, scoreInfo) -> {
            VarInts.writeUnsignedInt(buffer, scoreInfo.getType().ordinal());
            helper.writeString(buf, TYPES[scoreInfo.getType().ordinal()]);

            VarInts.writeLong(buf, scoreInfo.getScoreboardId());

            switch (scoreInfo.getType()) {
                case INVALID:
                    // The constant `true` a RemoveScore entry carries before its optional objective
                    // name. It is a real byte on the wire - see the r26_u4 schema dump's RemoveScore
                    // (1.26.44.3, network version 2168), where the field sits between Scoreboard Id
                    // and Objective Name with `"type": "bool", "value": true`.
                    //
                    // Omitting it against a peer that expects it makes every removal entry one byte
                    // short, and writing it to one that does not makes it one byte long. Neither
                    // throws: the reader takes the constant as the optional's presence flag and the
                    // absent-objective 0x00 that follows as a zero-length string, so the packet
                    // decodes "successfully" and the relayed copy is wrong. SetScore is a broadcast,
                    // so one scoreboard removal disconnects every player on the backend at once with
                    // BadPacket and no disconnect reason.
                    //
                    // Which shape is right is not a property of protocol 2168 - it is a property of
                    // the peer, and both shapes live under that one number. See
                    // BedrockCodecHelper_v2168#isRemoveScoreKeyedConstant.
                    if (carriesKeyedConstant(helper)) {
                        buf.writeBoolean(true);
                    }
                    helper.writeOptional(buf, o-> !o.isEmpty(), scoreInfo.getObjectiveId(), helper::writeString);
                    break;
                case ENTITY:
                case PLAYER:
                    if (scoreInfo.getObjectiveId().isEmpty() && log.isDebugEnabled()) {
                        log.debug("SetScorePacket with empty ObjectiveId");
                    }

                    helper.writeString(buf, scoreInfo.getObjectiveId().isEmpty() ? " " : scoreInfo.getObjectiveId());
                    buf.writeIntLE(scoreInfo.getScore());
                    VarInts.writeLong(buf, scoreInfo.getEntityId());
                    break;
                case FAKE:
                    if (scoreInfo.getObjectiveId().isEmpty() && log.isDebugEnabled()) {
                        log.debug("SetScorePacket with empty ObjectiveId");
                    }
                    if (scoreInfo.getName().isEmpty() && log.isDebugEnabled()) {
                        log.debug("SetScorePacket with empty Name");
                    }

                    helper.writeString(buf, scoreInfo.getObjectiveId().isEmpty() ? " " : scoreInfo.getObjectiveId());
                    buf.writeIntLE(scoreInfo.getScore());
                    helper.writeString(buf, scoreInfo.getName().isEmpty() ? " " : scoreInfo.getName());
                    break;
                default:
                    throw new IllegalStateException("ScoreInfo.ScorerType");
            }
        });
    }

    @Override
    public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, SetScorePacket packet) {
        helper.readArray(buffer, packet.getInfos(), buf -> {
            ScoreInfo.ScorerType type = ScoreInfo.ScorerType.values()[VarInts.readUnsignedInt(buffer)];
            helper.readString(buf); //type

            long scoreboardId = VarInts.readLong(buf);

            String objectiveId;
            int score;
            switch (type) {
                case INVALID:
                    if (carriesKeyedConstant(helper)) {
                        buf.readBoolean(); // the constant written above
                    }
                    objectiveId = helper.readOptional(buf, null, helper::readString);
                    return new ScoreInfo(scoreboardId, objectiveId == null ? "" : objectiveId, 0);
                case ENTITY:
                case PLAYER:
                    objectiveId = helper.readString(buf);
                    score = buf.readIntLE();
                    long entityId = VarInts.readLong(buf);
                    return new ScoreInfo(scoreboardId, objectiveId, score, type, entityId);
                case FAKE:
                    objectiveId = helper.readString(buf);
                    score = buf.readIntLE();
                    String name = helper.readString(buf);
                    return new ScoreInfo(scoreboardId, objectiveId, score, name);
                default:
                    throw new IllegalStateException("ScoreInfo.ScorerType");
            }
        });
    }

    /**
     * Whether this peer's release puts the keyed-setter constant in a {@code RemoveScore} entry.
     *
     * <p>A helper that is not a {@link BedrockCodecHelper_v2168} cannot have been told, so it gets
     * the current release's shape. Nothing in this tree builds 2168 on another helper; the branch
     * exists so a caller that hand-rolls one is not silently given the 1.26.40 layout.</p>
     */
    private static boolean carriesKeyedConstant(BedrockCodecHelper helper) {
        return !(helper instanceof BedrockCodecHelper_v2168 v2168) || v2168.isRemoveScoreKeyedConstant();
    }
}
