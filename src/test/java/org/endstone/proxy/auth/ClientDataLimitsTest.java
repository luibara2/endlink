package org.endstone.proxy.auth;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.cloudburstmc.protocol.bedrock.codec.v291.serializer.LoginSerializer_v291;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.cloudburstmc.protocol.common.util.VarInts;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The login batch may be megabytes so custom skins fit. These are the bounds that keep those
 * megabytes from being spent on JSON structure, which parses at ~79 bytes of heap per byte.
 */
class ClientDataLimitsTest {

    @Test
    void aLargeRealisticSkinPayloadPasses() {
        // A few very long base64 strings plus a modest amount of structure: what a custom skin
        // with geometry actually looks like.
        String blob = "A".repeat(2_000_000);
        StringBuilder json = new StringBuilder("{\"SkinData\":\"").append(blob)
                .append("\",\"SkinGeometryData\":\"").append(blob)
                .append("\",\"PersonaPieces\":[");
        for (int i = 0; i < 200; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"PieceId\":\"x\\\"y\",\"PieceType\":\"t\",\"IsDefault\":false,\"Tint\":[1,2,3]}");
        }
        json.append("]}");
        assertDoesNotThrow(() -> ClientDataLimits.checkPayloadShape(json.toString()));
    }

    @Test
    void tooManyTokensIsRefused() {
        String json = "[" + "0,".repeat(ClientDataLimits.MAX_TOKENS + 1) + "0]";
        assertThrows(IllegalArgumentException.class, () -> ClientDataLimits.checkPayloadShape(json));
    }

    @Test
    void deepNestingIsRefused() {
        String json = "[".repeat(ClientDataLimits.MAX_DEPTH + 1) + "]".repeat(ClientDataLimits.MAX_DEPTH + 1);
        assertThrows(IllegalArgumentException.class, () -> ClientDataLimits.checkPayloadShape(json));
    }

    @Test
    void structureInsideStringsIsNotCounted() {
        String json = "{\"a\":\"" + "[{,:".repeat(ClientDataLimits.MAX_TOKENS) + "\\\"\"}";
        assertDoesNotThrow(() -> ClientDataLimits.checkPayloadShape(json));
    }

    @Test
    void oversizedHeaderIsRefused() {
        String header = "e".repeat(ClientDataLimits.MAX_HEADER_BYTES + 1);
        assertThrows(IllegalArgumentException.class, () -> ClientDataLimits.checkHeader(header + ".p.s"));
        assertDoesNotThrow(() -> ClientDataLimits.checkHeader("eyJhbGciOiJFUzM4NCJ9.p.s"));
    }

    @Test
    void oversizedAuthJwtIsRefusedBeforeParsing() {
        byte[] auth = ("{\"chain\":[" + "[".repeat(LoginSerializer_v291.MAX_AUTH_JWT_BYTES) + "]}")
                .getBytes(StandardCharsets.UTF_8);
        ByteBuf jwt = Unpooled.buffer();
        jwt.writeIntLE(auth.length).writeBytes(auth).writeIntLE(0);
        ByteBuf packet = Unpooled.buffer();
        packet.writeInt(1);
        VarInts.writeUnsignedInt(packet, jwt.readableBytes());
        packet.writeBytes(jwt);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> LoginSerializer_v291.INSTANCE.deserialize(packet, null, new LoginPacket()));
        assertTrue(thrown.getMessage().contains("exceeds the maximum"), thrown.getMessage());
    }
}
