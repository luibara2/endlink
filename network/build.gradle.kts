/*
 * Copyright 2023 CloudburstMC
 *
 * CloudburstMC licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

plugins {
    alias(libs.plugins.nmcp.aggregation)
    `maven-publish`
}

repositories {
    mavenCentral()
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "com.gradleup.nmcp")
    apply(plugin = "maven-publish")
    apply(plugin = "signing")

    group = "dev.kastle.netty"
    version = rootProject.property("version") as String

    repositories {
        mavenLocal()
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(8))
        }
        withJavadocJar()
        withSourcesJar()
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                artifactId = "netty-${project.name}"

                from(components["java"])

                pom {
                    description.set(project.name)
                    name.set(project.name)
                    url.set("https://github.com/Kas-tle/NetworkCompatible")
                    inceptionYear.set("2024")
                    licenses {
                        license {
                            name.set("The Apache License, Version 2.0")
                            url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        }
                    }
                    developers {
                        developer {
                            name.set("Kas-tle")
                            organization.set("Kas-tle")
                            organizationUrl.set("https://github.com/Kas-tle")
                        }
                    }
                    scm {
                        connection.set("scm:git:git://github.com/Kas-tle/NetworkCompatible.git")
                        developerConnection.set("scm:git:ssh://github.com:Kas-tle/NetworkCompatible.git")
                        url.set("https://github.com/Kas-tle/NetworkCompatible")
                    }
                    ciManagement {
                        system.set("GitHub Actions")
                        url.set("https://github.com/Kas-tle/NetworkCompatible/actions")
                    }
                    issueManagement {
                        system.set("GitHub Issues")
                        url.set("https://github.com/Kas-tle/NetworkCompatible/issues")
                    }
                }
            }
        }
    }

    configure<SigningExtension> {
        if (System.getenv("PGP_SECRET") != null && System.getenv("PGP_PASSPHRASE") != null) {
            useInMemoryPgpKeys(System.getenv("PGP_SECRET"), System.getenv("PGP_PASSPHRASE"))
            sign(project.extensions.getByType(PublishingExtension::class).publications["maven"])
        }
    }

    tasks {
        named<JavaCompile>("compileJava") {
            options.encoding = "UTF-8"
        }
        named<Test>("test") {
            minHeapSize = "512m"
            maxHeapSize = "1024m"
            jvmArgs = listOf("-XX:MaxMetaspaceSize=512m")
            useJUnitPlatform()
        }
    }
}

dependencies {
    allprojects {
        nmcpAggregation(project(path))
    }
}


nmcpAggregation {
    centralPortal {
        project(":transport-raknet")
        project(":transport-nethernet")

        username.set(System.getenv("MAVEN_CENTRAL_USERNAME"))
        password.set(System.getenv("MAVEN_CENTRAL_PASSWORD"))
        
        publishingType.set("AUTOMATIC")
    }
}
