package org.endstone.proxy.command;

public record ProxyCommand(String name, String description) {
    public ProxyCommand {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        if (name.startsWith("/")) {
            throw new IllegalArgumentException("name must not include a leading slash");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description cannot be blank");
        }
    }
}
