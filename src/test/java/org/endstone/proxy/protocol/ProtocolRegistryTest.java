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

    /**
     * The deployment this codec was added for: Endstone still builds against 1.26.44, so a server
     * that lets its players update to 1.26.45 is running 2169 on one leg and 2168 on the other. If
     * this pair does not resolve, a 1.26.45 client is refused at the door.
     */
    @Test
    void a_1_26_45_clientReachesA_1_26_44_backend() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertTrue(registry.findClientCodec(2169).isPresent());
        ProtocolBinding binding = registry.findBinding(2169, 2168).orElseThrow();

        assertEquals(2169, binding.clientCodec().getProtocolVersion());
        assertEquals(2168, binding.backendCodec().getProtocolVersion());
        // No packet rewriting: the one field that differs is handled by each leg's own codec helper.
        assertSame(IdentityTranslator898.INSTANCE, binding.translator());
    }

    @Test
    void a_1_26_45_clientStillReachesTheOlderBackendsBelow_2168() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        // Chained through 2168, which is the point of the graph being a graph.
        assertTrue(registry.findBinding(2169, 1001).isPresent());
        assertTrue(registry.findBinding(2169, 2169).isPresent());
        assertEquals(1001, registry.findBinding(2169, 1001).orElseThrow().backendCodec().getProtocolVersion());
    }

    @Test
    void theGraphStillOnlyRunsNewerToOlder() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        // A 1.26.44 client against a 1.26.45 backend is an upgrade edge the proxy does not own.
        // Nothing about adding 2169 may quietly create one.
        assertTrue(registry.findBinding(2168, 2169).isEmpty());
        assertTrue(registry.findBinding(1001, 2169).isEmpty());
    }

    /**
     * Endstone is moving from 1.26.44 to 1.26.45, and a fleet is mixed for as long as that takes.
     * Both backend legs have to resolve for the same 1.26.45 player, because the session's protocol
     * binding is re-resolved on every backend connect — a switch between the two is a switch between
     * these two bindings.
     */
    @Test
    void a_1_26_45_playerReachesBothA_2168_andA_2169_backend() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        ProtocolBinding toOldBackend = registry.findBinding(2169, 2168).orElseThrow();
        ProtocolBinding toNewBackend = registry.findBinding(2169, 2169).orElseThrow();

        assertEquals(2168, toOldBackend.backendCodec().getProtocolVersion());
        assertEquals(2169, toNewBackend.backendCodec().getProtocolVersion());
        // The client leg is the same codec either way; only the backend leg moves.
        assertEquals(2169, toOldBackend.clientCodec().getProtocolVersion());
        assertEquals(2169, toNewBackend.clientCodec().getProtocolVersion());
    }

    /**
     * A 1.26.45 backend must be detectable. Endstone 1.26.45 sets NetworkProtocolVersion to 2169, so
     * that is what its pong advertises and what backend.protocol=auto reads back; an unknown codec
     * there is refused as an unsupported backend before the player is ever told anything useful.
     */
    @Test
    void a_2169_backendIsAKnownBackendCodec() {
        ProtocolRegistry registry = ProtocolRegistry.createDefault();

        assertTrue(registry.findBackendCodec(2169).isPresent());
        assertEquals(2169, registry.findBackendCodec(2169).orElseThrow().getProtocolVersion());
        assertEquals("1.26.45", registry.findBackendCodec(2169).orElseThrow().getMinecraftVersion());
    }
}
