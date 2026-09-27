plugins {
    java
}

group = "cloud.alistair"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")

    testImplementation("io.papermc.paper:paper-api:26.2.build.129-stable")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.2:4.116.1")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}

// Everything except the loader. /smp reload swaps this in without a server restart.
val coreJar by tasks.registering(Jar::class) {
    archiveFileName.set("core.jar")
    destinationDirectory.set(layout.buildDirectory.dir("core"))
    from(sourceSets.main.get().output) {
        exclude("cloud/alistair/smp/loader/**", "plugin.yml")
    }
}

// The loader: what Paper loads. Only needs replacing (and a restart) when commands change.
tasks.jar {
    exclude("cloud/alistair/market/**", "config.yml", "shop.yml")
}

tasks.assemble {
    dependsOn(coreJar)
}

tasks.test {
    dependsOn(coreJar)
    systemProperty("smp.coreJar", coreJar.get().archiveFile.get().asFile.absolutePath)
    // Same Java as the server. Byte Buddy (inside MockBukkit) can't make its proxies on 26+.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    useJUnitPlatform()
    // MockBukkit's Byte Buddy proxies need to define classes reflectively.
    jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")
    testLogging {
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.withType<JavaCompile> {
    options.release.set(25)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
