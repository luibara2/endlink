import org.gradle.plugins.ide.eclipse.model.AccessRule
import org.gradle.plugins.ide.eclipse.model.AbstractClasspathEntry

plugins {
    id("eclipse")
}

dependencies {
    api(projects.bedrockCodec)
    api(libs.netty.transport.raknet)
    api(libs.snappy)
}

tasks.jar {
    manifest {
        attributes("Automatic-Module-Name" to "org.cloudburstmc.protocol.bedrock.connection")
    }
}

eclipse {
	classpath {
		file {
			whenMerged.add( object: Action<org.gradle.plugins.ide.eclipse.model.Classpath> {
				override fun execute( classpath: org.gradle.plugins.ide.eclipse.model.Classpath ) {
					val jre = classpath.entries.find {
						it is AbstractClasspathEntry &&
							it.path.contains("org.eclipse.jdt.launching.JRE_CONTAINER")
					} as AbstractClasspathEntry

					// make sun.misc accessible in Eclipse project
					// (when refreshing Gradle project in buildship)
					jre.accessRules.add(AccessRule("accessible", "sun/misc/**"))

					// remove trailing slash from jre path
					if (jre.path.endsWith("/")) jre.path = jre.path.substring(0, jre.path.length - 1)
				}
			} )
		}
	}
}

eclipse {
	classpath {
		file {
			whenMerged.add( object: Action<org.gradle.plugins.ide.eclipse.model.Classpath> {
				override fun execute( classpath: org.gradle.plugins.ide.eclipse.model.Classpath ) {
					val jre = classpath.entries.find {
						it is AbstractClasspathEntry &&
							it.path.contains("org.eclipse.jdt.launching.JRE_CONTAINER")
					} as AbstractClasspathEntry

					// make sun.misc accessible in Eclipse project
					// (when refreshing Gradle project in buildship)
					jre.accessRules.add(AccessRule("accessible", "sun/misc/**"))

					// remove trailing slash from jre path
					if (jre.path.endsWith("/")) jre.path = jre.path.substring(0, jre.path.length - 1)
				}
			} )
		}
	}
}