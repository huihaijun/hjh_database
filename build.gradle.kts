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
    // 1. 创建一个名为 copyToServer 的复制任务
    val copyToServer by registering(Copy::class) {
        // 【重要】把这里替换为你本地测试服务器 plugins 文件夹的绝对路径！
        // 注意：Windows 路径里的斜杠需要使用双反斜杠 \\ 或者单正斜杠 /
        val pluginDir = "D:/mc/hjh1.21.3/plugins"
        // 如果你最终采纳了【方案二】(不再使用 Shadow，使用 libraries)：
        dependsOn(jar)
        from(jar)
        into(pluginDir)
    }

    // 2. 将此任务与 build 绑定
    build {
        // finalizedBy 的意思是：当 build 任务大功告成后，紧接着执行 copyToServer
        finalizedBy(copyToServer)
    }
}