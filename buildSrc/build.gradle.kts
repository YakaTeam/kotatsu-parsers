plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.korte)
    implementation(libs.simplexml)
    implementation(libs.kotlinx.coroutines.core)
}
