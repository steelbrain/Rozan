plugins {
    id("rozan.jvm.library")
}

tasks.named<Test>("test") {
    // src/test/resources/vectors holds copies of docs/protocol/vectors so the module's tests are
    // self-contained; VectorFilesTest reads the originals through this path to catch drift.
    val docsVectors = rootProject.layout.projectDirectory.dir("docs/protocol/vectors")
    inputs
        .dir(docsVectors)
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("docsVectors")
    systemProperty("rozan.fairplay.docsVectors", docsVectors.asFile.relativeTo(projectDir).path)
}
