plugins {
    java
    `maven-publish`
    id("com.gradleup.shadow") version "9.4.1"
}

group = "org.nethergames.proxytransport"
version = "2.0.7-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    maven("https://repo.waterdog.dev/snapshots")
    maven("https://repo.opencollab.dev/main/")
    mavenCentral()
}

// QUIC (+ netty-handler, which WaterdogPE doesn't bundle) is embedded under quic-libs/ and injected onto
// netty's classloader at runtime; see QuicLibraryInstaller. These are NOT shaded as loose classes.
val quicLibs by configurations.creating

dependencies {
    compileOnly("dev.waterdog.waterdogpe:waterdog:2.0.4-SNAPSHOT")
    compileOnly("io.netty.incubator:netty-incubator-codec-classes-quic:0.0.74.Final")

    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    // Shaded into the plugin jar.
    implementation("com.github.luben:zstd-jni:1.5.5-4")

    quicLibs("io.netty:netty-handler:4.1.135.Final") { isTransitive = false }
    quicLibs("io.netty.incubator:netty-incubator-codec-classes-quic:0.0.74.Final") { isTransitive = false }
    quicLibs("io.netty.incubator:netty-incubator-codec-native-quic:0.0.74.Final:linux-x86_64") { isTransitive = false }
}

tasks {
    processResources {
        filesMatching("plugin.yml") {
            expand("project" to mapOf("version" to project.version))
        }
        from(quicLibs) {
            into("quic-libs")
            rename { fileName ->
                when {
                    fileName.contains("native") -> "netty-incubator-codec-native-quic.jar"
                    fileName.contains("handler") -> "netty-handler.jar"
                    else -> "netty-incubator-codec-classes-quic.jar"
                }
            }
        }
    }

    shadowJar {
        archiveFileName.set("ProxyTransport.jar")
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    build {
        dependsOn(shadowJar)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "proxy-transport"
            artifact(tasks.shadowJar)
        }
    }
    repositories {
        maven {
            name = "nethergamesmc"
            url = uri("https://repo.nethergames.org/repository/NetherGamesMC/")
            credentials {
                username = System.getenv("REPO_USERNAME")
                password = System.getenv("REPO_PASSWORD")
            }
        }
    }
}
