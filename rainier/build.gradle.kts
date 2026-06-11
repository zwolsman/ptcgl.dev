plugins {
	kotlin("jvm")
	id("io.spring.dependency-management")
}

group = "com.zwolsman.ptcgl"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencyManagement {
	imports {
		mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
	}
}

dependencies {
	implementation("com.squareup.okhttp3:okhttp:4.12.0")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.slf4j:slf4j-api")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("ch.qos.logback:logback-classic")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
