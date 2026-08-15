plugins {
    java
}

description = "JMH benchmarks for J-Scheduler."

dependencies {
    implementation(project(":"))
    implementation("org.openjdk.jmh:jmh-core:1.37")
    annotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.register<JavaExec>("jmh") {
    description = "Runs the full JMH benchmark suite."
    group = "benchmark"
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets.main.get().runtimeClasspath
    val resultFile = layout.buildDirectory.file("reports/jmh/results.json")
    doFirst {
        resultFile.get().asFile.parentFile.mkdirs()
        println("Benchmark host: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        println("Available processors: ${Runtime.getRuntime().availableProcessors()}")
        println("JDK: ${System.getProperty("java.runtime.version")}")
    }
    args("-rf", "json", "-rff", resultFile.get().asFile.absolutePath)
    providers.gradleProperty("jmhArgs").orNull
        ?.split(Regex("\\s+"))
        ?.filter(String::isNotBlank)
        ?.let(::args)
}

tasks.register<JavaExec>("jmhSmoke") {
    description = "Runs a short harness smoke test; results are not performance evidence."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    mainClass = "org.openjdk.jmh.Main"
    classpath = sourceSets.main.get().runtimeClasspath
    args("BatchExecutionBenchmark.platformThreads", "-p", "batchSize=100",
            "-wi", "0", "-i", "1", "-r", "100ms", "-f", "1")
}
