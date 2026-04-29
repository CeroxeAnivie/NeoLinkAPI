plugins {
    `java-library`
    `maven-publish`
    signing
    jacoco
}

group = "top.ceroxe.api"
version = "6.0.2"
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
    api("fun.ceroxe.api:ceroxe-core:1.0.0")

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
