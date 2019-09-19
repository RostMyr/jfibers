Example build.gradle
```gradle
apply plugin: 'java'

sourceCompatibility = 1.8
targetCompatibility = 1.8

buildscript {
    repositories {
        mavenLocal()
    }
    

	dependencies {
		classpath 'com.jfibers.plugin:jfiber-gradle-plugin:1.0.0'
		classpath 'com.github.rostmyr.jfibers:jfibers-core:1.0.0-SNAPSHOT'
	}
}

repositories {
	mavenLocal()
	mavenCentral()
}
dependencies {
	compile group:'com.github.rostmyr.jfibers', name:'jfibers-core', version:'1.0.0-SNAPSHOT'
}

apply plugin: 'com.jfibers.plugin'

// Copying of the patched classes
task copyClassesToBin(type:Copy, dependsOn:[jar]) {
   copy {
     from "${buildDir}/classes/java/main"   
     into "${projectDir}/bin"
    }
}

jar {
    manifest {
        attributes 'Main-Class': 'Main'
    }
    from configurations.runtime.collect { zipTree(it) }
}
```
gradle jfiber jar
