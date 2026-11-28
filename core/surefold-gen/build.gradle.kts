// Random generators for property checks (spec 0318). Depends on surefold and nothing else;
// NoOtherDependenciesTest asserts that on the classpath a consumer gets.

dependencies {
    api(project(":surefold"))
    testImplementation(project(":surefold-assert"))
}

tasks.test {
    val mainRuntime = configurations.runtimeClasspath
    inputs.files(mainRuntime).withPropertyName("mainRuntimeClasspath")
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dsurefold.gen.runtimeClasspath=" + mainRuntime.get().joinToString(File.pathSeparator) { it.name },
            )
        },
    )
}
