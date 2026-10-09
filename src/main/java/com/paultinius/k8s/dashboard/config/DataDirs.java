package com.paultinius.k8s.dashboard.config;

import java.nio.file.Path;

/** Resolves the dashboard's data directory the same way for every component that saves state to it. */
public final class DataDirs {

    private DataDirs() {
    }

    public static Path resolve(DashboardProperties properties) {
        String configured = properties.getCluster().getDataDir();
        return Path.of(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".k8s-dashboard").toString()
                : configured);
    }
}
