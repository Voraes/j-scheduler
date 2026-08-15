plugins {
    `java-library`
    `maven-publish`
    signing
}

description = "Spring Boot auto-configuration and observability for J-Scheduler."

dependencies {
    api(project(":"))
    api(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    api("org.springframework.boot:spring-boot-starter")

    annotationProcessor("org.springframework.boot:spring-boot-autoconfigure-processor:4.1.0")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:4.1.0")
    compileOnly("io.micrometer:micrometer-core")
    compileOnly("org.springframework.boot:spring-boot-health")
    compileOnly("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("com.fasterxml.jackson.core:jackson-annotations")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-Xlint:-processing")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "J-Scheduler Spring Boot Starter"
                description = project.description
                url = "https://github.com/Voraes/j-scheduler"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/license/mit"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "voraes"
                        name = "Voraes"
                        url = "https://github.com/Voraes"
                    }
                }
                scm {
                    connection = "scm:git:https://github.com/Voraes/j-scheduler.git"
                    developerConnection = "scm:git:ssh://git@github.com/Voraes/j-scheduler.git"
                    url = "https://github.com/Voraes/j-scheduler"
                }
            }
        }
    }
    repositories {
        maven {
            name = "staging"
            url = rootProject.layout.buildDirectory.dir("repository").get().asFile.toURI()
        }
    }
}

val signingKey = providers.gradleProperty("signingKey")
    .orElse(providers.environmentVariable("SIGNING_KEY"))
val signingPassword = providers.gradleProperty("signingPassword")
    .orElse(providers.environmentVariable("SIGNING_PASSWORD"))

signing {
    if (signingKey.isPresent) {
        useInMemoryPgpKeys(signingKey.get(), signingPassword.orNull)
        sign(publishing.publications["mavenJava"])
    }
}
