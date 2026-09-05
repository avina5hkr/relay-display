plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.avinash.relaydisplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.avinash.relaydisplay"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            // R8 on: shrink, optimise and obfuscate. Keep rules live in src/main/keepRules,
            // which AGP combines automatically.
            optimization {
                enable = true
            }
            // Release builds are unsigned here on purpose; both target phones install the debug
            // APK. Add a signingConfig before publishing anywhere.
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // minSdk 23 still needs desugaring for the java.* APIs used by ZXing and by our
        // own code (java.util.Objects, try-with-resources on AutoCloseable, java.time).
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.datastore.preferences.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

/**
 * Generates open-source notices from the *resolved* dependency metadata.
 *
 * Every entry comes from the licence block published in each artifact's own POM. Nothing is
 * hand-written and nothing is guessed: a dependency whose POM declares no licence is emitted as
 * "not declared in POM" rather than being assigned one. That is the difference between a notices
 * screen that is accurate and one that merely looks thorough.
 *
 * Wired through the Variant API so the asset is produced for every variant and survives R8 --
 * assets are not subject to code shrinking, so the notices cannot be optimised away.
 */
abstract class GenerateLicenseNotices : DefaultTask() {

    @get:Input
    abstract val moduleIds: ListProperty<String>

    @get:InputFiles
    @get:org.gradle.api.tasks.PathSensitive(org.gradle.api.tasks.PathSensitivity.NONE)
    abstract val pomFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val byModule = pomFiles.files.associateBy { it.nameWithoutExtension }
        val rendered = buildString {
            appendLine("RelayDisplay third-party open-source notices")
            appendLine("Generated from the resolved release runtime classpath. Do not edit by hand.")
            appendLine("An entry with no licence line declares none in its published POM.")
            appendLine()
            for (id in moduleIds.get()) {
                val parts = id.split(":")
                if (parts.size < 3) continue
                appendLine(id)
                val licences = byModule["${parts[1]}-${parts[2]}"]?.let(::parseLicences).orEmpty()
                if (licences.isEmpty()) {
                    appendLine("    licence: not declared in POM")
                } else {
                    for ((name, url) in licences) {
                        appendLine("    licence: $name")
                        if (url != null) appendLine("    url: $url")
                    }
                }
                appendLine()
            }
        }
        val out = outputDir.get().asFile
        out.mkdirs()
        File(out, "third_party_licenses.txt").writeText(rendered)
    }

    /** Reads only the `<licenses>` block. No inference, no fallback table. */
    private fun parseLicences(pom: File): List<Pair<String, String?>> = try {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = false }
            .newDocumentBuilder()
            .parse(pom)
        val nodes = doc.getElementsByTagName("license")
        (0 until nodes.length).mapNotNull { index ->
            val element = nodes.item(index) as? org.w3c.dom.Element ?: return@mapNotNull null
            val name = element.getElementsByTagName("name").item(0)?.textContent?.trim()
            val url = element.getElementsByTagName("url").item(0)?.textContent?.trim()
            if (name.isNullOrEmpty()) null else name to url?.ifEmpty { null }
        }
    } catch (e: Exception) {
        // A malformed or missing POM yields no licence rather than a build failure; the entry
        // then reads "not declared in POM", which is the honest outcome.
        emptyList()
    }
}

val licenseModuleIds: Provider<List<String>> = providers.provider {
    configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allDependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .map { it.selected.id }
        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
        .map { "${it.group}:${it.module}:${it.version}" }
        .distinct()
        .sorted()
}

val licensePomFiles: Provider<Set<File>> = providers.provider {
    try {
        val notation = licenseModuleIds.get().map { dependencies.create("$it@pom") }.toTypedArray()
        val detached = configurations.detachedConfiguration(*notation)
        detached.isTransitive = false
        detached.resolvedConfiguration.lenientConfiguration.artifacts.map { it.file }.toSet()
    } catch (e: Exception) {
        logger.warn("Licence notices: could not resolve POMs (${e.javaClass.simpleName})")
        emptySet()
    }
}

val generateLicenseNotices = tasks.register<GenerateLicenseNotices>("generateLicenseNotices") {
    moduleIds.set(licenseModuleIds)
    pomFiles.from(licensePomFiles)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            generateLicenseNotices,
            GenerateLicenseNotices::outputDir,
        )
    }
}
