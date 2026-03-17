import tasks.ReportGenerateTask

plugins {
	`java-library`
	`maven-publish`
	alias(libs.plugins.kotlin.jvm)
	alias(libs.plugins.ksp)
}

group = "org.koitharu"
version = System.getenv("RELEASE_VERSION") ?: "1.2.3-SNAPSHOT"

tasks.test {
	useJUnitPlatform()
}

ksp {
	arg("summaryOutputDir", "${projectDir}/.github")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
	compilerOptions {
		freeCompilerArgs.addAll(
			"-opt-in=kotlin.RequiresOptIn",
			"-opt-in=kotlin.contracts.ExperimentalContracts",
			"-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
			"-opt-in=org.koitharu.kotatsu.parsers.InternalParsersApi",
		)
	}
}

kotlin {
	jvmToolchain(11)
	explicitApiWarning()
	sourceSets["main"].kotlin.srcDirs("build/generated/ksp/main/kotlin")
}

publishing {
	publications {
		create<MavenPublication>("mavenJava") {
			from(components["java"])
			groupId = "org.koitharu"
			artifactId = "kotatsu-parsers"
			version = project.version.toString()
		}
	}
	repositories {
		maven {
			name = "GitHubPackages"
			url = uri("https://maven.pkg.github.com/YakaTeam/kotatsu-parsers")
			credentials(HttpHeaderCredentials::class) {
				name = "Authorization"
				value = "Bearer ${System.getenv("GITHUB_TOKEN")}"
			}
			authentication {
				create<HttpHeaderAuthentication>("header")
			}
		}
	}
}

dependencies {
	implementation(libs.kotlinx.coroutines.core)
	implementation(libs.okhttp)
	implementation(libs.okio)
	implementation(libs.json)
	implementation(libs.androidx.collection)
	api(libs.jsoup)

	ksp(project(":kotatsu-parsers-ksp"))

	testImplementation(libs.junit.api)
	testImplementation(libs.junit.engine)
	testImplementation(libs.junit.params)
	testRuntimeOnly(libs.junit.launcher)
	testImplementation(libs.kotlinx.coroutines.test)
	testImplementation(libs.quickjs)
}

tasks.register<ReportGenerateTask>("generateTestsReport")
