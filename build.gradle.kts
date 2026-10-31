buildscript {
    configurations.classpath { resolutionStrategy.failOnNonReproducibleResolution() }
}

plugins {
    alias(libs.plugins.android.library) apply false
    // built-in Kotlin の KGP を AGP の要求する版より高い catalog の版に解決する
    alias(libs.plugins.kotlin.jvm) apply false
}

subprojects {
    buildscript.configurations.configureEach { resolutionStrategy.failOnNonReproducibleResolution() }
    configurations.configureEach {
        if (name.endsWith("CompileClasspath", ignoreCase = true) || name.endsWith("RuntimeClasspath", ignoreCase = true)) {
            resolutionStrategy.activateDependencyLocking()
        } else {
            resolutionStrategy.failOnNonReproducibleResolution()
        }
    }
}
