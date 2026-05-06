import java.time.Duration
import java.time.temporal.ChronoUnit

plugins {
    base
    id("com.gradleup.nmcp.aggregation") version "1.4.4"
}

repositories {
    mavenCentral()
}

group = "top.ceroxe.api"
version = "7.1.12"

val apiVersion = version.toString()

nmcpAggregation {
    centralPortal {
        username.set(providers.gradleProperty("centralUsername"))
        password.set(providers.gradleProperty("centralPassword"))
        publishingType = "AUTOMATIC"
        publicationName = "NeoLinkAPI-$version"
        validationTimeout = Duration.of(30, ChronoUnit.MINUTES)
    }

    publishAllProjectsProbablyBreakingProjectIsolation()
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    apply(plugin = "java-library")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")
    apply(plugin = "jacoco")

    repositories {
        if (providers.gradleProperty("useMavenLocal").map(String::toBoolean).orElse(false).get()) {
            mavenLocal()
        }
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension>("java") {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(if (project.name == "desktop") 21 else 17))
        }
        withSourcesJar()
        withJavadocJar()
    }

    if (project.name == "desktop") {
        extensions.configure<SourceSetContainer>("sourceSets") {
            named("main") {
                java.srcDir(rootProject.layout.projectDirectory.dir("common/src/main/java"))
            }
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(if (project.name == "desktop") 21 else 17)
    }

    tasks.withType<Javadoc>().configureEach {
        options.encoding = "UTF-8"
        (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    }

    tasks.withType<ProcessResources>().configureEach {
        val resourceVersion = project.version.toString()
        filteringCharset = "UTF-8"
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        inputs.property("apiVersion", apiVersion)
        filesMatching("api.properties") {
            expand("version" to resourceVersion)
        }
    }

    extensions.configure<JacocoPluginExtension>("jacoco") {
        toolVersion = "0.8.11"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
        finalizedBy(tasks.named("jacocoTestReport"))
        jvmArgs(
            "-Dfile.encoding=UTF-8",
            "-Dsun.stdout.encoding=UTF-8",
            "-Dsun.stderr.encoding=UTF-8",
            "-Dconsole.encoding=UTF-8",
            "-Dsun.jnu.encoding=UTF-8"
        )
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }

    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("mavenJava") {
                from(components["java"])
                artifactId = when (project.name) {
                    "shared" -> "neolinkapi-shared"
                    "desktop" -> "neolinkapi-desktop"
                    else -> "neolinkapi-${project.name}"
                }

                pom {
                    name.set(
                        when (project.name) {
                            "shared" -> "NeoLinkAPI Shared"
                            "desktop" -> "NeoLinkAPI Desktop"
                            else -> "NeoLinkAPI ${project.name}"
                        }
                    )
                    description.set(
                        when (project.name) {
                            "shared" -> "Shared protocol models and utilities for NeoLink clients."
                            "desktop" -> "Desktop JVM NeoLink TCP/UDP tunnel client."
                            else -> "NeoLinkAPI module ${project.name}."
                        }
                    )
                    url.set("https://github.com/CeroxeAnivie/NeoLinkAPI")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("CeroxeAnivie")
                            name.set("Ceroxe")
                            email.set("1591117599@qq.com")
                            organization.set("Ceroxe")
                            url.set("https://github.com/CeroxeAnivie")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/CeroxeAnivie/NeoLinkAPI.git")
                        developerConnection.set("scm:git:ssh://github.com:CeroxeAnivie/NeoLinkAPI.git")
                        url.set("https://github.com/CeroxeAnivie/NeoLinkAPI")
                    }
                }
            }
        }

        repositories {
            maven {
                name = "centralStaging"
                url = layout.buildDirectory.dir("repos/central-staging").get().asFile.toURI()
            }
        }
    }

    extensions.configure<SigningExtension>("signing") {
        useGpgCmd()
        sign(extensions.getByType<PublishingExtension>().publications["mavenJava"])
    }
}

project(":shared") {
    dependencies {
        "api"("com.google.code.gson:gson:2.11.0")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    }
}

project(":desktop") {
    dependencies {
        "api"(project(":shared"))
        "api"("top.ceroxe.api:ceroxe-core:2.0.0")
        "implementation"("top.ceroxe.api:ceroxe-detector:2.0.0")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.2")
    }

    tasks.register("printTransparencyRuntimeClasspath") {
        group = "verification"
        description = "Prints the runtime classpath required by the transparency check launcher."
        dependsOn(tasks.named("testClasses"))
        doLast {
            val sourceSets = project.extensions.getByType<SourceSetContainer>()
            val outputEntries = listOf(
                sourceSets["main"].output,
                sourceSets["test"].output
            ).flatMap { it.files }

            val runtimeEntries = sourceSets["test"].runtimeClasspath.files
            val classpathEntries = LinkedHashSet<File>()
            classpathEntries.addAll(outputEntries)
            classpathEntries.addAll(runtimeEntries)

            println(classpathEntries.joinToString(File.pathSeparator) { it.absolutePath })
        }
    }
}

tasks.register("printTransparencyRuntimeClasspath") {
    dependsOn(":desktop:printTransparencyRuntimeClasspath")
}

tasks.named("build") {
    dependsOn(subprojects.map { ":${it.name}:build" })
}

tasks.register("test") {
    group = "verification"
    description = "Runs all Java module tests."
    dependsOn(subprojects.map { ":${it.name}:test" })
}
