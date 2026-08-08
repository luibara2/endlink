package org.endstone.proxy;

/**
 * @deprecated renamed to {@link Endlink}. Kept so an old launch script or service unit naming this
 *         class keeps working; it does nothing but forward. Remove once every deployment names
 *         {@code org.endstone.proxy.Endlink} (the jar's manifest already does).
 */
@Deprecated(forRemoval = true)
public final class EndstoneProxy {
    private EndstoneProxy() {
    }

    public static void main(String[] args) throws Exception {
        Endlink.main(args);
    }
}
