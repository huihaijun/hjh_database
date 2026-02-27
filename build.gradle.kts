plugins {
    // 对应你的 Kotlin 版本
    kotlin("jvm") version "2.0.21"
    java
}

group = "com"
version = "1.0-SNAPSHOT"

kotlin {
    // 对应你 pom.xml 中的 Java 21 目标版本
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    // 对应你原来的 repositories 和 pluginRepositories
    maven("https://maven.aliyun.com/repository/public")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Maven 中的 <scope>provided</scope> 在 Gradle 中对应 compileOnly
    compileOnly("io.papermc.paper:paper-api:1.21.3-R0.1-SNAPSHOT")

    // Lombok 配置
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // 需要被打包进插件的依赖使用 implementation
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
}

tasks {
    // 替代 pom.xml 中的 <filtering>true</filtering>
    // 这样 plugin.yml 里的 '${project.version}' 就能被正确替换
    processResources {
        filesMatching("plugin.yml") {
            expand("version" to project.version)
        }
    }
}