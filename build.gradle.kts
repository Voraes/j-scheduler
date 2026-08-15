plugins {
    id("java")
    id("maven-publish")
    id("jacoco")
    id("checkstyle")
    id("signing")
    id("org.springframework.boot") version "4.1.0" apply false
}

group = "io.github.voraes"
version = "2.0.0-SNAPSHOT"
description = "A lightweight resilient task scheduling engine for modern Java applications."

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    repositories {
        mavenCentral()
    }

    plugins.withType<JavaPlugin> {
        pluginManager.apply("checkstyle")

        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release = 21
            options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
        }

        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }

        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
        }

        tasks.named("check") {
            dependsOn(tasks.withType<Javadoc>())
        }

        extensions.configure<CheckstyleExtension> {
            toolVersion = "13.9.0"
            configFile = rootProject.file("config/checkstyle/checkstyle.xml")
            isShowViolations = true
        }
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:all,-missing", true)
}

tasks.check {
    dependsOn(tasks.javadoc)
}

checkstyle {
    toolVersion = "13.9.0"
    configFile = file("config/checkstyle/checkstyle.xml")
    isShowViolations = true
}

val stressTest by sourceSets.creating

dependencies {
    add(stressTest.implementationConfigurationName, sourceSets.main.get().output)
}

configurations[stressTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[stressTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.register<Test>("stressTest") {
    description = "Runs opt-in high-contention scheduler stress tests."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = stressTest.output.classesDirs
    classpath = stressTest.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name = "J-Scheduler"
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
            url = layout.buildDirectory.dir("repository").get().asFile.toURI()
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
