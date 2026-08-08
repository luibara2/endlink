package org.endstone.proxy.plugin;

import org.endstone.proxy.config.ProxyConfig;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The addon contract: a jar in {@code plugins/} is found and enabled, its contributions reach the
 * proxy, and a broken one cannot take the proxy down with it.
 *
 * <p>That last part is the reason this class exists. Endlink's whole value is that Bedrock players
 * keep their server; an optional addon throwing during startup must cost those players nothing.</p>
 */
class PluginManagerTest {

    @Test
    void anAddonIsDiscoveredEnabledAndItsContributionsCollected() throws Exception {
        Path plugins = Files.createTempDirectory("endlink-plugins");
        writeAddonJar(plugins.resolve("Contributing.jar"), "Contributing", ContributingPlugin.class);

        PluginManager manager = new PluginManager(plugins, config());
        manager.enableAll();

        assertTrue(!manager.isEmpty(), "the addon should have been enabled");
        assertEquals(1, manager.protocolUpgrades().size(), "its protocol edge should have reached the proxy");
        assertEquals(CanonicalProtocol.V1_26_30, manager.protocolUpgrades().get(0).older());
        assertEquals(1, manager.trustedListeners().size(), "its listener request should have reached the proxy");
        assertEquals(19136, manager.trustedListeners().get(0).address().getPort());

        assertTrue(Files.isDirectory(plugins.resolve("Contributing")),
                "an addon's data folder must exist before it is enabled, since that is where it reads its config");

        manager.proxyReady();
        assertTrue(ContributingPlugin.readyCalled, "onProxyReady runs once the listeners are up");

        manager.disableAll();
        assertTrue(ContributingPlugin.disabledCalled);
    }

    @Test
    void anAddonThatThrowsIsSkippedRatherThanFatal() throws Exception {
        Path plugins = Files.createTempDirectory("endlink-plugins");
        writeAddonJar(plugins.resolve("Broken.jar"), "Broken", ThrowingPlugin.class);
        writeAddonJar(plugins.resolve("Working.jar"), "Working", ContributingPlugin.class);

        PluginManager manager = new PluginManager(plugins, config());

        assertDoesNotThrow(manager::enableAll,
                "a broken addon must not stop the proxy from starting; Bedrock players do not care that "
                        + "an optional addon is broken");
        assertEquals(1, manager.trustedListeners().size(),
                "the working addon must still have been enabled alongside the broken one");
    }

    /**
     * A first start has no plugins directory, and that is the ordinary case for a plain Bedrock proxy
     * rather than an error. It still gets created: "drop the addon into plugins/" is only useful advice
     * if the folder is visibly there, and an operator told to create it themselves will sooner or later
     * create it somewhere the proxy is not looking.
     */
    @Test
    void aMissingPluginsDirectoryIsCreatedRatherThanIgnored() throws Exception {
        Path plugins = Files.createTempDirectory("endlink-root").resolve("plugins");

        PluginManager manager = new PluginManager(plugins, config());
        assertDoesNotThrow(manager::enableAll);

        assertTrue(Files.isDirectory(plugins), "the plugins directory must exist after startup");
        assertTrue(manager.isEmpty());
        assertTrue(manager.protocolUpgrades().isEmpty());
        assertTrue(manager.trustedListeners().isEmpty());
    }

    /** A jar with no descriptor is somebody else's file in the folder, and is left alone. */
    @Test
    void aJarWithoutADescriptorIsIgnored() throws Exception {
        Path plugins = Files.createTempDirectory("endlink-plugins");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(plugins.resolve("random.jar")))) {
            jar.putNextEntry(new JarEntry("README.txt"));
            jar.write("not an addon".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }

        PluginManager manager = new PluginManager(plugins, config());
        assertDoesNotThrow(manager::enableAll);
        assertTrue(manager.isEmpty());
    }

    private static ProxyConfig config() {
        return ProxyConfig.from(new Properties());
    }

    /**
     * Packages a plugin class from the test classpath into a jar with a descriptor. The class is also
     * visible to the parent loader, which is fine — the loader delegates to the parent anyway, and what
     * is under test is discovery and lifecycle rather than isolation.
     */
    private static void writeAddonJar(Path jarPath, String name, Class<?> pluginClass) throws IOException {
        String classResource = pluginClass.getName().replace('.', '/') + ".class";
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            jar.putNextEntry(new JarEntry("endlink-plugin.properties"));
            jar.write(("name=" + name + "\nmain=" + pluginClass.getName() + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();

            jar.putNextEntry(new JarEntry(classResource));
            try (InputStream input = pluginClass.getClassLoader().getResourceAsStream(classResource)) {
                if (input == null) {
                    throw new IOException("Could not read " + classResource + " from the test classpath");
                }
                copy(input, jar);
            }
            jar.closeEntry();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
    }

    public static final class ContributingPlugin implements EndlinkPlugin {
        static volatile boolean readyCalled;
        static volatile boolean disabledCalled;

        @Override
        public void onEnable(PluginContext context) {
            context.addProtocolUpgrade(CanonicalProtocol.V1_26_30, CanonicalProtocol.V1_26_40,
                    org.endstone.proxy.protocol.IdentityTranslator898.INSTANCE);
            context.addTrustedListener(new TrustedListenerSpec(
                    new InetSocketAddress("127.0.0.1", 19136),
                    CanonicalProtocol.V1_26_30.codec(),
                    "secret",
                    "*"
            ));
        }

        @Override
        public void onProxyReady() {
            readyCalled = true;
        }

        @Override
        public void onDisable() {
            disabledCalled = true;
        }
    }

    public static final class ThrowingPlugin implements EndlinkPlugin {
        @Override
        public void onEnable(PluginContext context) {
            throw new IllegalStateException("this addon is broken on purpose");
        }
    }
}
