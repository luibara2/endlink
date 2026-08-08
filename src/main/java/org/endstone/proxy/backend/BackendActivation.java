package org.endstone.proxy.backend;

public interface BackendActivation {
    void onReady(BackendSession backend);

    void onStartGame(BackendSession backend);

    void onFailure(BackendSession backend, Exception exception);
}
