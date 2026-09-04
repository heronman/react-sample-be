plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.dependency.management)
    alias(libs.plugins.springdoc.openapi.gradle)
}

group = "net.agl.react"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot
    implementation(platform(libs.spring.cloud.bom))
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-web")
    // On bootRun's classpath (so the forked run used to generate docs/openapi.json
    // has springdoc's auto-config) but excluded from bootJar, so it never ships or
    // exposes /v3/api-docs, /swagger-ui.html at runtime.
    developmentOnly(libs.springdoc.openapi.webmvc.ui)

    // Kotlin add-ons
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.core:jackson-annotations")

    //
    implementation(libs.kasechange)

    // Testing
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

openApi {
    outputDir.set(layout.projectDirectory.dir("docs"))
    outputFileName.set("openapi.json")
}

// Runs a Gradle task inside the ephemeral Docker build container (see
// docker-compose.build.yml), for hosts without a local JDK/Gradle install.
fun registerDockerTask(name: String, taskDescription: String, dockerizedTask: String) {
    tasks.register<Exec>(name) {
        group = "docker"
        description = taskDescription
        val args = mutableListOf("docker", "compose", "-f", "docker-compose.build.yml", "run", "--rm")
        if (!System.getProperty("os.name").lowercase().contains("win")) {
            val uid = ProcessBuilder("id", "-u").start().inputStream.bufferedReader().readText().trim()
            val gid = ProcessBuilder("id", "-g").start().inputStream.bufferedReader().readText().trim()
            args += listOf("-u", "$uid:$gid")
        }
        args += listOf("build", dockerizedTask)
        commandLine(args)
    }
}

registerDockerTask("dockerBuild", "Runs the Gradle build inside the Docker build container.", "build")
registerDockerTask(
    "dockerGenerateOpenApiDocs",
    "Generates docs/openapi.json inside the Docker build container.",
    "generateOpenApiDocs",
)
