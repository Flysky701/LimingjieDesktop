import org.gradle.api.tasks.SourceSetContainer

val sets = extensions.getByType<SourceSetContainer>()
val mainOutput = sets.getByName("main").output
val evaluation = sets.create("evaluation") {
    compileClasspath += mainOutput
    runtimeClasspath += mainOutput
}
configurations.getByName(evaluation.implementationConfigurationName).extendsFrom(configurations.getByName("implementation"))
dependencies.add(evaluation.implementationConfigurationName, project(":shared"))

tasks.register<JavaExec>("evaluate") {
    group = "verification"
    description = "Evaluate real OCR crops through unchanged upstream matchers and report coverage gaps"
    dependsOn(evaluation.classesTaskName)
    classpath = evaluation.runtimeClasspath
    mainClass.set("com.landosol.toolbox.desktop.ocr.evaluation.OcrEvaluationKt")
    args(rootProject.projectDir.parentFile.absolutePath, layout.buildDirectory.dir("reports").get().asFile.absolutePath)
    maxHeapSize = "1536m"
}
