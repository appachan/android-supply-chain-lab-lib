plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    signing
}

group = "com.github.appachan"
version = providers.gradleProperty("releaseVersion").getOrElse("0.0.0-dev")

android {
    namespace = "com.github.appachan.supplychainlab.lib"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig.minSdk = libs.versions.minSdk.get().toInt()
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") { withSourcesJar() }
    }
}

dependencies {
    testImplementation(libs.junit4)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "android-supply-chain-lab-lib"
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/appachan/android-supply-chain-lab-lib")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        providers.environmentVariable("SIGNING_KEY").orNull,
        providers.environmentVariable("SIGNING_PASSWORD").orNull,
    )
    sign(publishing.publications)
}
