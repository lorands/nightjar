dependencyResolutionManagement {
    // Reuse the root version catalog inside buildSrc
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}
