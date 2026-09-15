plugins {
    id("mylibrary.kotlin.library")
}

// The domain layer is a plain JVM library on purpose: business models, repository contracts,
// document-decoder contracts and use cases, with zero Android or decoder-library leakage.
//
// `javax.inject` is exposed as `api` rather than `implementation` because every `@Inject`
// constructor here is a public API that Dagger has to see when `:core:core-data` builds the graph.
dependencies {
    api(libs.javax.inject)

    implementation(project(":core:core-common"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
}
