package org.endstone.proxy.protocol;

import org.cloudburstmc.protocol.bedrock.codec.v898.Bedrock_v898;
import org.cloudburstmc.protocol.bedrock.codec.v924.Bedrock_v924;
import org.cloudburstmc.protocol.bedrock.codec.v944.Bedrock_v944;
import org.cloudburstmc.protocol.bedrock.codec.v975.Bedrock_v975;
import org.cloudburstmc.protocol.bedrock.packet.PlayStatusPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolRegistryTest {
    @Test
    void defaultRegistryAcceptsSupportedNewerProtocols() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertTrue(registry.findClientCodec(898).isPresent());
        assertTrue(registry.findClientCodec(924).isPresent());
        assertTrue(registry.findClientCodec(944).isPresent());
        assertTrue(registry.findClientCodec(975).isPresent());
        assertTrue(registry.findClientCodec(1001).isPresent());
        assertTrue(registry.findClientCodec(2168).isPresent());
        assertTrue(registry.findClientCodec(897).isEmpty());
        assertTrue(registry.findClientCodec(976).isEmpty());
        assertEquals(CanonicalProtocol.values().length, registry.supportedClients().size());
    }

    @Test
    void registryResolvesSupportedClientBackendPairs() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertTrue(registry.findBinding(898, 898).isPresent());
        assertTrue(registry.findBinding(924, 898).isPresent());
        assertTrue(registry.findBinding(944, 898).isPresent());
        assertTrue(registry.findBinding(975, 898).isPresent());
        assertTrue(registry.findBinding(944, 944).isPresent());
        assertTrue(registry.findBinding(975, 944).isPresent());
        assertTrue(registry.findBinding(975, 975).isPresent());
        assertTrue(registry.findBinding(898, 944).isEmpty());
        assertTrue(registry.findBinding(924, 944).isEmpty());
        assertTrue(registry.findBinding(944, 975).isEmpty());
        assertEquals(944, registry.findBinding(975, 944).orElseThrow().backendCodec().getProtocolVersion());
        assertSame(ModernClientTo944Translator.INSTANCE, registry.findBinding(975, 944).orElseThrow().translator());
    }

    @Test
    void canonicalProtocolUsesBedrockV898Codec() {
        assertSame(Bedrock_v898.CODEC, CanonicalProtocol.V1_21_130.codec());
        assertEquals(898, CanonicalProtocol.V1_21_130.protocolVersion());
        assertEquals("1.21.130", CanonicalProtocol.V1_21_130.minecraftVersion());
    }

    @Test
    void newerClientProtocolsUseExpectedCodecs() {
        assertSame(Bedrock_v924.CODEC, CanonicalProtocol.V1_26_0.codec());
        assertSame(Bedrock_v944.CODEC, CanonicalProtocol.V1_26_10.codec());
        assertSame(Bedrock_v975.CODEC, CanonicalProtocol.V1_26_20.codec());
        // Derived, not hardcoded: this assertion existed to catch a registry that forgot the newest
        // codec, and pinning a literal made it fail on every release instead.
        assertEquals(CanonicalProtocol.newest().protocolVersion(),
                ProtocolRegistry.createDefault().advertisedClientCodec().getProtocolVersion());
    }

    @Test
    void chainsNewestClientDownToOlderBackends() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        // Single hop returns the raw adjacent translator.
        assertSame(ModernClientTo975Translator.INSTANCE, registry.findBinding(1001, 975).orElseThrow().translator());

        // Multi-hop gaps are auto-chained (1001 -> 975 -> 944 and 1001 -> 975 -> 898).
        assertTrue(registry.findBinding(1001, 944).orElseThrow().translator() instanceof ChainedPacketTranslator);
        assertTrue(registry.findBinding(1001, 898).orElseThrow().translator() instanceof ChainedPacketTranslator);
        assertEquals(898, registry.findBinding(1001, 898).orElseThrow().backendCodec().getProtocolVersion());

        // The chain found is the shortest path of adjacent steps.
        assertEquals(2, registry.findPath(1001, 944).orElseThrow().size());
        assertEquals(2, registry.findPath(1001, 898).orElseThrow().size());
        assertSame(ModernClientTo975Translator.INSTANCE, registry.findPath(1001, 944).orElseThrow().get(0));
        assertSame(ModernClientTo944Translator.INSTANCE, registry.findPath(1001, 944).orElseThrow().get(1));

        // Same version is identity; no upgrade path exists.
        assertSame(IdentityTranslator898.INSTANCE, registry.findBinding(1001, 1001).orElseThrow().translator());
        assertTrue(registry.findBinding(944, 1001).isEmpty());
    }

    @Test
    void newerClientProtocolsUseModernToCanonicalTranslator() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertSame(IdentityTranslator898.INSTANCE, registry.findBinding(898, 898).orElseThrow().translator());
        assertSame(ModernClientTo898Translator.INSTANCE, registry.findBinding(924, 898).orElseThrow().translator());
        assertSame(ModernClientTo898Translator.INSTANCE, registry.findBinding(944, 898).orElseThrow().translator());
        assertSame(ModernClientTo898Translator.INSTANCE, registry.findBinding(975, 898).orElseThrow().translator());
        assertSame(IdentityTranslator898.INSTANCE, registry.findBinding(944, 944).orElseThrow().translator());
        assertSame(ModernClientTo944Translator.INSTANCE, registry.findBinding(975, 944).orElseThrow().translator());
        assertSame(IdentityTranslator898.INSTANCE, registry.findBinding(975, 975).orElseThrow().translator());
    }

    @Test
    void unsupportedProtocolsReturnBedrockLoginFailureStatus() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, registry.unsupportedStatus(897));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, registry.unsupportedStatus(899));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, registry.unsupportedStatus(976));
        // 1002 sits between two supported protocols now that 1.26.40 is 2168, so it reads as an old
        // client rather than an old server.
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_CLIENT_OLD, registry.unsupportedStatus(1002));
        assertEquals(PlayStatusPacket.Status.LOGIN_FAILED_SERVER_OLD,
                registry.unsupportedStatus(CanonicalProtocol.newest().protocolVersion() + 1));
    }
}
