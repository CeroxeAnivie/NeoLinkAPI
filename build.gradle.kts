import java.time.Duration
import java.time.temporal.ChronoUnit

plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
    id("com.gradleup.nmcp.aggregation") version "1.4.4"
}

group = "top.ceroxe.api"
val apiVersion = "7.1.3"
version = apiVersion
val mavenArtifactId = "neolinkapi"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    api("top.ceroxe.api:ceroxe-core:2.0.0")
    implementation("top.ceroxe.api:ceroxe-detector:2.0.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

tasks.withType<ProcessResources>().configureEach {
    filteringCharset = "UTF-8"
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    inputs.property("apiVersion", apiVersion)
    filesMatching("api.properties") {
        expand(
            "version" to project.version.toString()
        )
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
    finalizedBy(tasks.jacocoTestReport)
    jvmArgs(
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-Dconsole.encoding=UTF-8",
        "-Dsun.jnu.encoding=UTF-8"
    )
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = mavenArtifactId

            pom {
                name.set("NeoLinkAPI")
                description.set("Embeddable Java API client for NeoLink TCP/UDP tunnels.")
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

signing {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}

nmcpAggregation {
    centralPortal {
        username = providers.gradleProperty("centralUsername").orNull.orEmpty()
        password = providers.gradleProperty("centralPassword").orNull.orEmpty()
        publishingType = "AUTOMATIC"
        publicationName = "NeoLinkAPI-$version"
        validationTimeout = Duration.of(30, ChronoUnit.MINUTES)
    }

    publishAllProjectsProbablyBreakingProjectIsolation()
}
