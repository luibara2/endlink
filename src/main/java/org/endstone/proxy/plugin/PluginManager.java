package org.endstone.proxy.plugin;

import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.endstone.proxy.config.ProxyConfig;
import org.endstone.proxy.protocol.CanonicalProtocol;
import org.endstone.proxy.protocol.PacketTranslator;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.jar.JarFile;

/**
 * Finds, enables and shuts down the addons in {@code plugins/}.
 *
 * <p>An addon is any jar with an {@code endlink-plugin.properties} at its root naming a {@code name}
 * and a {@code main}. There is no registry, no versioning and no dependency resolution: drop the jar
 * in, it runs; take it out, it does not. That is the entire contract, and it is what makes
 * "{@code java -jar Endlink.jar} with an empty {@code plugins/}" a plain Bedrock proxy rather than a
 * proxy with its Java support switched off.</p>
 *
 * <p><b>Failures are contained.</b> A jar that cannot be read, an addon whose class will not load, or
 * one that throws while enabling is reported and skipped. Bedrock players must not lose their server
 * because an optional addon is broken, which is the same reason the bridge has always been
 * allowed to fail without taking the proxy with it.</p>
 */
public final class PluginManager {
    private static final String DESCRIPTOR = "endlink-plugin.properties";

    private final Path pluginsDirectory;
    private final ProxyConfig proxyConfig;
    private final List<LoadedPlugin> plugins = new ArrayList<>();
    private final List<ProtocolUpgrade> protocolUpgrades = new ArrayList<>();
    private final List<TrustedListenerSpec> trustedListeners = new ArrayList<>();

    public record ProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator) {
    }

    private record LoadedPlugin(String name, EndlinkPlugin plugin, URLClassLoader classLoader) {
    }

    public PluginManager(Path pluginsDirectory, ProxyConfig proxyConfig) {
        this.pluginsDirectory = pluginsDirectory;
        this.proxyConfig = proxyConfig;
    }

    public List<ProtocolUpgrade> protocolUpgrades() {
        return List.copyOf(protocolUpgrades);
    }

    public List<TrustedListenerSpec> trustedListeners() {
        return List.copyOf(trustedListeners);
    }

    /** Discovers and enables every addon. Contributions are collected but nothing is bound yet. */
    public void enableAll() {
        if (pluginsDirectory == null) {
            return;
        }
        // Create it even when there is nothing to load. An operator who has been told "drop the addon
        // in plugins/" should find the folder already there, exactly as they would on Paper or
        // Velocity — being asked to create it themselves invites creating it in the wrong place.
        if (!Files.isDirectory(pluginsDirectory)) {
            try {
                Files.createDirectories(pluginsDirectory);
            } catch (IOException exception) {
                System.out.printf("Could not create the plugins directory %s: %s. Addons will not load.%n",
                        pluginsDirectory, exception);
                return;
            }
        }
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(pluginsDirectory, "*.jar")) {
            entries.forEach(jars::add);
        } catch (IOException exception) {
            System.out.printf("Could not read the plugins directory %s: %s.%n", pluginsDirectory, exception);
            return;
        }
        jars.sort(Path::compareTo);

        for (Path jar : jars) {
            try {
                load(jar);
            } catch (Throwable throwable) {
                System.out.printf("Addon %s failed to load and was skipped: %s.%n", jar.getFileName(), throwable);
            }
        }
    }

    private void load(Path jar) throws Exception {
        String name;
        String mainClass;
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            var entry = jarFile.getEntry(DESCRIPTOR);
            if (entry == null) {
                // Not an addon. Someone's unrelated jar in the folder is not an error.
                return;
            }
            Properties descriptor = new Properties();
            try (InputStream input = jarFile.getInputStream(entry)) {
                descriptor.load(input);
            }
            name = descriptor.getProperty("name", "").trim();
            mainClass = descriptor.getProperty("main", "").trim();
        }
        if (name.isEmpty() || mainClass.isEmpty()) {
            System.out.printf("Addon %s has an %s without both 'name' and 'main'; skipped.%n",
                    jar.getFileName(), DESCRIPTOR);
            return;
        }

        // Parent is this classloader on purpose: an addon is written against the proxy's API and has
        // to see it. The proxy never sees the addon, which is the direction that matters.
        URLClassLoader classLoader = new URLClassLoader(
                name,
                new URL[]{jar.toUri().toURL()},
                PluginManager.class.getClassLoader()
        );
        Object instance = Class.forName(mainClass, true, classLoader).getDeclaredConstructor().newInstance();
        if (!(instance instanceof EndlinkPlugin plugin)) {
            classLoader.close();
            System.out.printf("Addon %s: %s does not implement EndlinkPlugin; skipped.%n", name, mainClass);
            return;
        }

        Path dataFolder = pluginsDirectory.resolve(name);
        Files.createDirectories(dataFolder);
        plugin.onEnable(new Context(name, dataFolder));
        plugins.add(new LoadedPlugin(name, plugin, classLoader));
        System.out.printf("Enabled addon %s.%n", name);
    }

    /** Tells every enabled addon that the listeners are up. */
    public void proxyReady() {
        for (LoadedPlugin loaded : plugins) {
            try {
                loaded.plugin().onProxyReady();
            } catch (Throwable throwable) {
                System.out.printf("Addon %s failed to start: %s.%n", loaded.name(), throwable);
            }
        }
    }

    /** Disables addons in reverse enable order. Never throws. */
    public void disableAll() {
        for (int i = plugins.size() - 1; i >= 0; i--) {
            LoadedPlugin loaded = plugins.get(i);
            try {
                loaded.plugin().onDisable();
            } catch (Throwable ignored) {
                // Shutting down; a misbehaving addon must not stop the rest from shutting down.
            }
            try {
                loaded.classLoader().close();
            } catch (IOException ignored) {
                // as above
            }
        }
        plugins.clear();
    }

    public boolean isEmpty() {
        return plugins.isEmpty();
    }

    private final class Context implements PluginContext {
        private final String name;
        private final Path dataFolder;

        private Context(String name, Path dataFolder) {
            this.name = name;
            this.dataFolder = dataFolder;
        }

        @Override
        public Path dataFolder() {
            return dataFolder;
        }

        @Override
        public ProxyConfig proxyConfig() {
            return proxyConfig;
        }

        @Override
        public void info(String message) {
            System.out.printf("[%s] %s%n", name, message);
        }

        @Override
        public void addProtocolUpgrade(CanonicalProtocol older, CanonicalProtocol newer, PacketTranslator translator) {
            protocolUpgrades.add(new ProtocolUpgrade(older, newer, translator));
        }

        @Override
        public void addTrustedListener(TrustedListenerSpec spec) {
            trustedListeners.add(spec);
        }
    }

    /** Exposed so the listener can describe what it is about to bind. */
    public static BedrockCodec advertisedCodecOf(TrustedListenerSpec spec) {
        return spec.advertisedCodec();
    }
}
