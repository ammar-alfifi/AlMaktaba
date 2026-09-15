import com.mylibrary.buildlogic.addCatalogDependencies

/**
 * Adds Room + KSP to a module and pins the schema export directory.
 *
 * Schemas are exported to `<module>/schemas` and are meant to be committed: they are the source of
 * truth for verifying migrations in Room's migration tests.
 */
plugins {
    id("com.google.devtools.ksp")
}

addCatalogDependencies("implementation", "androidx-room-runtime", "androidx-room-ktx")
addCatalogDependencies("ksp", "androidx-room-compiler")

extensions.configure<com.google.devtools.ksp.gradle.KspExtension> {
    arg("room.schemaLocation", "${projectDir}/schemas")

    // Java code generation, deliberately — the opposite of the usual recommendation.
    //
    // Room's Kotlin writer formats the schema version through the JVM's default locale, so on a
    // machine whose locale is Arabic the generated code contains `RoomOpenDelegate(١, …)` — an
    // Arabic-Indic digit where Kotlin requires `1` — and the module fails to compile with a syntax
    // error pointing at a file nobody wrote. KSP runs the processor in a forked worker JVM, which
    // takes its locale from the environment, so `-Duser.language` on the Gradle daemon never
    // reaches it. The Java writer appends numbers with `StringBuilder`, which is locale-independent,
    // so the generated schema code is correct on every machine without any environment setup.
    arg("room.generateKotlin", "false")
}
